/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.grab.grazel.migrate.dependencies

import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.exec.bazelCommand
import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.di.GradleServices
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspacePlan
import com.grab.grazel.gradle.dependencies.model.WorkspaceRenderPlan
import com.grab.grazel.util.NoOpProgressLogger
import com.grab.grazel.util.WORKSPACE
import com.grab.grazel.util.ansiCyan
import com.grab.grazel.util.ansiGreen
import com.grab.grazel.util.isSuccess
import com.grab.grazel.util.startOperation
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.LogLevel.QUIET
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.internal.logging.progress.ProgressLogger
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal interface ArtifactPinner {

    fun pinArtifacts(
        workspaceFile: File,
        workspacePlan: WorkspacePlan,
        workspaceRenderPlan: WorkspaceRenderPlan,
        mavenInstallRepositoryInputs: MavenInstallRepositoryInputs = MavenInstallRepositoryInputs(emptyMap()),
        gradleServices: GradleServices,
        logger: Logger,
        localMavenResolutionContextFactory: ((Map<String, List<ResolvedDependency>>) -> LocalMavenResolutionPinContext)? = null,
    ): Boolean

    /**
     * Ensure that the following [bazelBlock] is safe to run any bazel command that might be dependent
     * on pinning, for example if maven_install.json gets corrupted or deleted, this ensures the
     * command is retried after fixing (usually just updating WORKSPACE file to remove pinning).
     *
     * Instead of failing command directly, pass the [ExecResult] obtain from [ExecOperations] instead
     * in the [bazelBlock]
     *
     * @param logger logger to log progress
     * @param bazelBlock block of code that needs to be run, must return the [BazelLogParsingOutputStream]
     *        and the [ExecResult] of the bazel command that is run inside the block
     */
    fun ensureSafeToRun(
        logger: Logger,
        gradleServices: GradleServices,
        bazelBlock: () -> Pair<BazelLogParsingOutputStream, ExecResult>
    )
}

private const val MAVEN_INSTALL_JSON_MARKER = "maven_install_json "
private const val PINNED_MAVEN_INSTALL_MARKER = "#maven_install_json"
private val COMMENTED_PINNED_LOAD_REGEX = Regex(
    """(?m)^#(load\("@[^"]+//:defs\.bzl", [A-Za-z0-9_]+_pinned_maven_install = "pinned_maven_install"\))"""
)
private val COMMENTED_PINNED_CALL_REGEX = Regex("""(?m)^#([A-Za-z0-9_]+_pinned_maven_install\(\))""")
private val ACTIVE_MAVEN_INSTALL_JSON_REGEX = Regex("""(?m)^(\s*)maven_install_json """)
private val ACTIVE_PINNED_LOAD_REGEX = Regex(
    """(?m)^(?!#)(load\("@[^"]+//:defs\.bzl", [A-Za-z0-9_]+_pinned_maven_install = "pinned_maven_install"\))"""
)
private val ACTIVE_PINNED_CALL_REGEX = Regex("""(?m)^(?!#)([A-Za-z0-9_]+_pinned_maven_install\(\))""")

@Singleton
internal class DefaultArtifactPinner
@Inject
constructor(
    private val grazelExtension: GrazelExtension
) : ArtifactPinner {

    private val artifactPinningEnabled: Boolean
        get() = grazelExtension.rules.mavenInstall.artifactPinning.enabled.get()

    private fun pin(workspaceFile: File) {
        workspaceFile.writeText(
            workspaceFile.readText()
                .replace("#$MAVEN_INSTALL_JSON_MARKER", MAVEN_INSTALL_JSON_MARKER)
                .replace(COMMENTED_PINNED_LOAD_REGEX, "$1")
                .replace(COMMENTED_PINNED_CALL_REGEX, "$1")
        )
    }

    private fun unpin(workspaceFile: File) {
        workspaceFile.writeText(
            workspaceFile.readText()
                .replace(ACTIVE_MAVEN_INSTALL_JSON_REGEX, "$1#$MAVEN_INSTALL_JSON_MARKER")
                .replace(ACTIVE_PINNED_LOAD_REGEX, "#$1")
                .replace(ACTIVE_PINNED_CALL_REGEX, "#$1")
        )
    }

    private fun failWhenOutOfDate(workspaceFile: File, enable: Boolean) {
        workspaceFile.writeText(
            workspaceFile.readText().replace(
                "fail_if_repin_required = ${(!enable).toString().capitalize()}",
                "fail_if_repin_required = ${enable.toString().capitalize()}"
            )
        )
    }

    /**
     * Determine if we have to run pinning artifacts again. There are two major cases that is checked
     * for
     *   1. First time run where no maven install json was generated. In that case, we return early
     *      and force pinning to run again
     *   2. Incremental run where maven install json already exists but might be out of date. In that
     *      case, run the build with `fail_if_repin_required=true` and check if build fails due to
     *      out of date maven install json.
     */
    internal fun shouldRunPinning(
        workspaceFile: File,
        gradleServices: GradleServices,
        parentProgress: ProgressLogger,
        logger: Logger,
        pinnableRepos: Map<String, List<ResolvedDependency>>,
        logOutput: Boolean = false
    ): Boolean {
        val progressLoggerFactory = gradleServices.progressLoggerFactory
        val progress = progressLoggerFactory.startOperation<DefaultArtifactPinner>(
            "Checking pin status",
            parentProgress
        )
        logger.quiet("Checking if artifacts should be repinned".ansiCyan)
        val mavenInstallJsonMissing = workspaceFile.useLines { lines ->
            lines.any { line -> line.contains(PINNED_MAVEN_INSTALL_MARKER) }
        }
        if (mavenInstallJsonMissing) {
            // If we detect maven install json is missing for any repo, we run pinning again
            // to regenerate the file.
            return true
        } else {
            failWhenOutOfDate(workspaceFile, true)

            fun checkRepoOutOfDate(mavenRepo: String, rootArtifacts: List<ResolvedDependency>): Boolean {
                val dep = selectPinStatusProbeArtifact(rootArtifacts)
                val (group, name) = dep.shortId.split(":")
                progress.progress("Checking $mavenRepo's pin status")
                val target = MavenDependency(
                    repo = mavenRepo,
                    group = group,
                    name = name
                ).toString()
                val args = listOf(target, "--nobuild")
                val outputStream = BazelLogParsingOutputStream(
                    logger = logger,
                    level = QUIET,
                    progressLogger = parentProgress,
                    mavenRepo = mavenRepo,
                    logOutput = logOutput
                )
                gradleServices.execOperations.bazelCommand(
                    logger = logger,
                    command = "build",
                    *args.toTypedArray(),
                    ignoreExit = true,
                    errorOutputStream = outputStream,
                )
                return outputStream.isOutOfDate
            }

            return (
                pinnableRepos.any { (mavenRepo, rootArtifacts) ->
                    checkRepoOutOfDate(mavenRepo, rootArtifacts)
                }
            ).also {
                // Revert the changes to the workspace file
                failWhenOutOfDate(workspaceFile, false)
                progress.completed()
            }
        }
    }


    internal fun determinePinningTarget(layout: ProjectLayout, mavenRepo: String): String {
        val installJson = "${mavenRepo}_install.json"
        return if (layout.projectDirectory.file(installJson).asFile.exists()) {
            "@unpinned_${mavenRepo}//:pin"
        } else {
            "@${mavenRepo}//:pin"
        }
    }

    internal fun cleanupStaleMavenInstallJsons(layout: ProjectLayout, activeMavenRepos: Set<String>) {
        val activeInstallJsons = activeMavenRepos.mapTo(mutableSetOf()) { "${it}_install.json" }
        layout.projectDirectory.asFile
            .listFiles { file ->
                file.isFile &&
                    (file.name == "maven_install.json" || file.name.endsWith("_maven_install.json")) &&
                    file.name !in activeInstallJsons
            }
            .orEmpty()
            .forEach(File::delete)
    }

    override fun pinArtifacts(
        workspaceFile: File,
        workspacePlan: WorkspacePlan,
        workspaceRenderPlan: WorkspaceRenderPlan,
        mavenInstallRepositoryInputs: MavenInstallRepositoryInputs,
        gradleServices: GradleServices,
        logger: Logger,
        localMavenResolutionContextFactory: ((Map<String, List<ResolvedDependency>>) -> LocalMavenResolutionPinContext)?,
    ): Boolean {
        val progressLoggerFactory = gradleServices.progressLoggerFactory

        val progressLogger = progressLoggerFactory.startOperation("Pin maven artifacts")
        val allRepos = collectPinnableMavenInstallRepos(
            workspacePlan = workspacePlan,
            workspaceRenderPlan = workspaceRenderPlan
        )
        cleanupStaleMavenInstallJsons(gradleServices.layout, allRepos.keys)

        val shouldRun = shouldRunPinning(
            workspaceFile,
            gradleServices,
            progressLogger,
            logger,
            pinnableRepos = allRepos
        )
        val layout = gradleServices.layout

        if (shouldRun) {
            val localMavenStartNanos = System.nanoTime()
            val localMavenContext = localMavenResolutionContextFactory?.invoke(allRepos)
            val localMavenWorkspace = localMavenContext?.let { context ->
                LocalMavenPinningWorkspace(
                    workspaceFile = workspaceFile,
                    rootDirectory = layout.projectDirectory.asFile,
                    repositoryRewrite = context.repositoryRewrite,
                    repositoryInputs = mavenInstallRepositoryInputs,
                    metadataOnlyShortIds = context.metadataOnlyShortIds
                )
            }
            logger.quiet("Repinning all artifacts".ansiCyan)
            val generatePinScripts = {
                allRepos.mapValues { (mavenRepoName, _) ->
                    val scriptPath = layout
                        .buildDirectory
                        .file("grazel/maven/${mavenRepoName}_pin.sh").apply {
                            get().asFile.parentFile.mkdirs()
                        }

                    val pinningTarget = determinePinningTarget(layout, mavenRepoName)
                    val args = listOf(
                        pinningTarget,
                        "--script_path=${scriptPath.get().asFile.absolutePath}",
                    )

                    progressLogger.progress("Pinning $mavenRepoName")

                    val outputStream = BazelLogParsingOutputStream(
                        logger = logger,
                        level = QUIET,
                        progressLogger = progressLogger,
                        mavenRepo = mavenRepoName,
                    )

                    val result = gradleServices.execOperations.bazelCommand(
                        logger = logger,
                        command = "run",
                        *args.toTypedArray(),
                        ignoreExit = true,
                        errorOutputStream = outputStream
                    )
                    scriptPath to result
                }.values
            }
            val pinScripts = localMavenWorkspace?.withProxyRepositories(generatePinScripts) ?: generatePinScripts()
            val isSuccess = pinScripts.all { it.second.isSuccess }
            if (isSuccess) {
                pin(workspaceFile)
                val workQueue = gradleServices.workerExecutor.noIsolation()
                pinScripts.forEach { (script, _) ->
                    workQueue.submit(PinningWorkAction::class.java) { pinScript.set(script) }
                }
                workQueue.await()
                localMavenWorkspace?.reconstructActiveLockfiles(allRepos.keys)
                if (localMavenContext != null) {
                    validateLocalMavenReconstruction(
                        workspaceFile = workspaceFile,
                        gradleServices = gradleServices,
                        parentProgress = progressLogger,
                        logger = logger,
                        pinnableRepos = allRepos
                    )
                    logLocalMavenResolutionSummary(
                        logger = logger,
                        stats = localMavenContext.stats(),
                        elapsedNanos = System.nanoTime() - localMavenStartNanos
                    )
                }
                progressLogger.completed()
                return true
            } else {
                progressLogger.completed(null, true)
                throw RuntimeException("Failed to pin artifacts")
            }
        } else {
            logger.quiet("Skipping pinning artifacts as they are up-to-date".ansiGreen)
            return true
        }
    }

    private fun validateLocalMavenReconstruction(
        workspaceFile: File,
        gradleServices: GradleServices,
        parentProgress: ProgressLogger,
        logger: Logger,
        pinnableRepos: Map<String, List<ResolvedDependency>>,
    ) {
        val stillRequiresPinning = shouldRunPinning(
            workspaceFile = workspaceFile,
            gradleServices = gradleServices,
            parentProgress = parentProgress,
            logger = logger,
            pinnableRepos = pinnableRepos,
            logOutput = true
        )
        if (stillRequiresPinning) {
            throw RuntimeException(
                "Local Maven resolution reconstructed lockfiles were rejected by " +
                    "rules_jvm_external 6.10 signature validation. Disable " +
                    "experiments.localMavenResolution or fix the lockfile reconstruction."
            )
        }
    }

    private fun logLocalMavenResolutionSummary(
        logger: Logger,
        stats: LocalMavenProxyStats,
        elapsedNanos: Long,
    ) {
        logger.quiet(
            "Local Maven resolution served ${stats.artifactHits} artifacts from Gradle index, " +
                "${stats.gradlePomHits} POMs from Gradle cache, " +
                "${stats.originFallbacks} unknown metadata POMs from origin, " +
                "${stats.alternateArtifactMisses} known alternate artifact misses, in " +
                "${TimeUnit.NANOSECONDS.toMillis(elapsedNanos)}ms".ansiGreen
        )
    }

    override fun ensureSafeToRun(
        logger: Logger,
        gradleServices: GradleServices,
        bazelBlock: () -> Pair<BazelLogParsingOutputStream, ExecResult>
    ) {
        val projectDirectory = gradleServices.layout.projectDirectory
        val (outputStream, execResult) = bazelBlock()
        when {
            !artifactPinningEnabled -> execResult.assertNormalExitValue()
            outputStream.isOutOfDate && !execResult.isSuccess -> {
                logger.quiet("Recovering maven install json corruption".ansiCyan)

                unpin(workspaceFile = projectDirectory.file(WORKSPACE).asFile)
                // Delete all maven_install.jsons as they can be corrupted, let pinning handle generating
                // them again
                projectDirectory
                    .asFileTree
                    .matching { include("**maven_install.json") }
                    .forEach(File::delete)

                // Retry the block again after unpinning
                val (_, execResult) = bazelBlock()
                execResult.assertNormalExitValue()
            }

            !outputStream.isOutOfDate && !execResult.isSuccess -> {
                throw RuntimeException("Bazel command failed")
            }
        }
    }
}

internal fun collectPinnableMavenInstallRepos(
    workspacePlan: WorkspacePlan,
    workspaceRenderPlan: WorkspaceRenderPlan
): Map<String, List<ResolvedDependency>> =
    workspaceRenderPlan.materializedRepoNames
        .mapNotNull { repoName ->
            val repo = workspacePlan.repoPlan[repoName] ?: return@mapNotNull null
            repoName to repo.pinInputs
        }
        .filter { (_, pinInputs) -> pinInputs.isNotEmpty() }
        .toMap()

internal fun selectPinStatusProbeArtifact(pinInputs: List<ResolvedDependency>): ResolvedDependency =
    pinInputs.firstOrNull { dependency -> dependency.direct && dependency.overrideTarget == null }
        ?: pinInputs.firstOrNull { dependency -> dependency.overrideTarget == null }
        ?: pinInputs.first()

internal open class PinningWorkAction
@Inject
constructor(
    private val execOperations: ExecOperations
) : WorkAction<PinningWorkAction.Parameters> {

    private val logger = Logging.getLogger(PinningWorkAction::class.java)

    interface Parameters : WorkParameters {
        val pinScript: RegularFileProperty
    }

    @Inject
    override fun getParameters(): Parameters {
        throw UnsupportedOperationException("not implemented")
    }

    override fun execute() {
        val outputStream = BazelLogParsingOutputStream(
            logger = logger,
            level = QUIET,
            progressLogger = NoOpProgressLogger,
        )
        execOperations.exec {
            commandLine = listOf(
                parameters.pinScript.get().asFile.absolutePath,
            )
            standardOutput = outputStream
            errorOutput = outputStream
        }
    }
}
