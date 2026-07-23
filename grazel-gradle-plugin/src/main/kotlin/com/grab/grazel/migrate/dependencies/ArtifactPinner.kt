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
import com.grab.grazel.maven.LocalMavenResolutionStats
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
        localMavenResolutionContextFactory: LocalMavenResolutionPinContextFactory? = null,
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

    /**
     * Unpins [workspaceFile] in place when any lockfile it actively references
     * (`maven_install_json = "//:<name>"`) is missing from the workspace root, so that a bazel
     * invocation preceding pinning (e.g. buildifier bootstrap) never hits rules_jvm_external's
     * pinned-fetch path against a nonexistent lockfile. No-op, returning false with the file
     * untouched, when the workspace has no active `maven_install_json` line or every referenced
     * lockfile is present.
     *
     * @return true if the workspace was unpinned
     */
    fun unpinWorkspaceIfLockfilesMissing(workspaceFile: File): Boolean
}

// These patterns toggle the exact commented-out block that rules_jvm_external's own WORKSPACE
// codegen emits for pinning (the `maven_install_json` attribute plus the generated
// `..._pinned_maven_install` load/call). There is no structured way to flip "pinned mode" on a
// WORKSPACE file short of re-parsing and re-emitting it, so [pin]/[unpin] instead toggle the
// leading `#` via regex. This is only correct because the generated text format is stable; any
// change to how rules_jvm_external renders these lines (spacing, load alias naming, etc.) will
// silently break pinning without a compile-time signal.
private const val MAVEN_INSTALL_JSON_MARKER = "maven_install_json "
private const val PINNED_MAVEN_INSTALL_MARKER = "#maven_install_json"
private val COMMENTED_PINNED_LOAD_REGEX = Regex(
    """(?m)^#(load\("@[^"]+//:defs\.bzl", [A-Za-z0-9_]+_pinned_maven_install = "pinned_maven_install"\))"""
)
private val COMMENTED_PINNED_CALL_REGEX = Regex("""(?m)^#([A-Za-z0-9_]+_pinned_maven_install\(\))""")
private val ACTIVE_MAVEN_INSTALL_JSON_REGEX = Regex("""(?m)^(\s*)maven_install_json """)
private val ACTIVE_MAVEN_INSTALL_JSON_FILE_REGEX = Regex("""(?m)^\s*maven_install_json = "//:([^"]+)",""")
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

    override fun unpinWorkspaceIfLockfilesMissing(workspaceFile: File): Boolean {
        val referencedLockfiles = ACTIVE_MAVEN_INSTALL_JSON_FILE_REGEX
            .findAll(workspaceFile.readText())
            .map { match -> match.groupValues[1] }
            .toList()
        val missingLockfiles = referencedLockfiles
            .filterNot { name -> workspaceFile.parentFile.resolve(name).exists() }
        if (missingLockfiles.isEmpty()) return false
        Logging.getLogger(DefaultArtifactPinner::class.java).quiet(
            "Unpinning WORKSPACE: lockfile(s) ${missingLockfiles.joinToString()} missing — " +
                "pinning regenerates them"
        )
        unpin(workspaceFile)
        return true
    }

    private fun failWhenOutOfDate(workspaceFile: File, enable: Boolean) {
        workspaceFile.writeText(
            workspaceFile.readText().replace(
                "fail_if_repin_required = ${starlarkBoolean(!enable)}",
                "fail_if_repin_required = ${starlarkBoolean(enable)}"
            )
        )
    }

    /**
     * Probes pin status without actually repinning. The [PINNED_MAVEN_INSTALL_MARKER] check is a
     * cheap short-circuit: if the WORKSPACE still has a repo's `maven_install_json` attribute
     * commented out (the `#maven_install_json` marker), no maven_install.json has ever been
     * generated for it, so pinning is unconditionally required and we skip straight to `true`
     * rather than paying for a bazel build.
     *
     * Otherwise we temporarily flip `fail_if_repin_required` to `true` (see [failWhenOutOfDate]) so
     * that building a single probe target per repo fails fast with rules_jvm_external's own
     * out-of-date signal, which [BazelLogParsingOutputStream.isOutOfDate] parses from stderr. The
     * flag flip is reverted in the `.also { }` regardless of outcome so this method never leaves the
     * WORKSPACE in a mutated state - callers (including the [pinArtifacts] validation pass) rely on
     * that.
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
                progress.progress("Checking $mavenRepo's pin status")
                val target = MavenDependency.fromShortId(dep.shortId, mavenRepo).toString()
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
        val installJson = mavenInstallJsonName(mavenRepo)
        return if (layout.projectDirectory.file(installJson).asFile.exists()) {
            "@unpinned_${mavenRepo}//:pin"
        } else {
            "@${mavenRepo}//:pin"
        }
    }

    internal fun cleanupStaleMavenInstallJsons(layout: ProjectLayout, activeMavenRepos: Set<String>) {
        val activeInstallJsons = activeMavenRepos.mapTo(mutableSetOf(), ::mavenInstallJsonName)
        layout.projectDirectory.asFile
            .listFiles { file ->
                file.isFile &&
                    (file.name == "maven_install.json" || file.name.endsWith("_maven_install.json")) &&
                    file.name !in activeInstallJsons
            }
            .orEmpty()
            .forEach(File::delete)
    }

    /**
     * Full pin orchestration. Ordering here is load-bearing and mirrors why local Maven resolution
     * needs a temporary WORKSPACE:
     *
     * 1. Pin scripts are generated while the WORKSPACE is temporarily rewritten to point at local
     *    proxy repositories ([LocalMavenPinningWorkspace.withProxyRepositories]), because the RJE
     *    pin scripts generated in that state embed the proxy URLs. Only once that call returns
     *    (its `finally` has already restored the canonical WORKSPACE) and script generation
     *    succeeded do we snapshot baseline lockfiles
     *    ([LocalMavenPinningWorkspace.snapshotActiveLockfiles]) - this just reads the existing
     *    maven_install.json files already on disk and has no dependency on WORKSPACE content.
     * 2. The WORKSPACE is switched to pinned mode ([pin]) and the pin scripts are executed via
     *    worker actions only *after* [withProxyRepositories] has already restored the canonical
     *    WORKSPACE text (the `finally` inside that function runs before this lambda returns), so
     *    the maven_install.json committed to the repo never leaks proxy URLs.
     * 3. Reconstruction ([LocalMavenPinningWorkspace.reconstructActiveLockfiles]) then rewrites the
     *    just-pinned lockfile's proxy URLs back to canonical and reconciles it against the
     *    snapshotted baseline - it must run strictly after step 2's restore, never before.
     * 4. Only when local Maven resolution produced a reconstruction do we re-validate via
     *    [validateLocalMavenReconstruction] and log stats; a plain pin (no local Maven context) skips
     *    both since there is nothing to reconstruct.
     */
    override fun pinArtifacts(
        workspaceFile: File,
        workspacePlan: WorkspacePlan,
        workspaceRenderPlan: WorkspaceRenderPlan,
        mavenInstallRepositoryInputs: MavenInstallRepositoryInputs,
        gradleServices: GradleServices,
        logger: Logger,
        localMavenResolutionContextFactory: LocalMavenResolutionPinContextFactory?,
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
            val localMavenContext = localMavenResolutionContextFactory?.create(
                pinnableRepos = allRepos,
                repositoryInputs = mavenInstallRepositoryInputs
            )
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
                val baselineLockfiles = localMavenWorkspace
                    ?.snapshotActiveLockfiles(allRepos.keys)
                    .orEmpty()
                pin(workspaceFile)
                // RJE pin scripts embed repository URLs captured while WORKSPACE is temporarily proxied.
                // WORKSPACE is restored before execution so generated source stays canonical.
                val workQueue = gradleServices.workerExecutor.noIsolation()
                pinScripts.forEach { (script, _) ->
                    workQueue.submit(PinningWorkAction::class.java) { pinScript.set(script) }
                }
                workQueue.await()
                localMavenWorkspace?.reconstructActiveLockfiles(
                    activeMavenRepos = allRepos.keys,
                    baselineLockfilesByRepoName = baselineLockfiles
                )
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
                        stats = localMavenContext.stats.snapshot(),
                        elapsedNanos = System.nanoTime() - localMavenStartNanos
                    )
                }
                progressLogger.completed()
                return true
            } else {
                progressLogger.completed("", true)
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
        stats: LocalMavenResolutionStats,
        elapsedNanos: Long,
    ) {
        val servedLocally = stats.artifactHits + stats.gradlePomHits
        logger.quiet(
            ("Local Maven resolution: $servedLocally served locally " +
                "(artifacts=${stats.artifactHits}, poms=${stats.gradlePomHits}, " +
                "checksums=${stats.checksumHits}), " +
                "${stats.originFallbacks} fell through to origin " +
                "(known-component=${stats.knownComponentFallthroughs}, " +
                "metadata-only=${stats.metadataOnlyArtifactFallbacks}, " +
                "origin-failures=${stats.originFailures}), " +
                "${stats.alternateArtifactMisses} alternate probes skipped, " +
                "${stats.writeThroughCacheHits} cache hits, " +
                "${stats.requestFailures} request failures, " +
                "${stats.bytesServed} bytes served, in " +
                "${TimeUnit.NANOSECONDS.toMillis(elapsedNanos)}ms").ansiGreen
        )
    }

    /**
     * A maven_install.json can go corrupt (e.g. truncated by a killed process) in a way that makes
     * rules_jvm_external report the repo as out-of-date *and* fail the build, which looks identical
     * to a legitimate out-of-date-but-otherwise-healthy repo from the caller's perspective. The
     * combination `isOutOfDate && !isSuccess` is the only reliable signal that recovery (rather than
     * a hard failure) is the right move: it means the file cannot even be parsed to determine repin
     * necessity, not merely that it is stale. Recovery unpins the WORKSPACE and deletes all
     * maven_install.json files so the next pinning pass regenerates them from scratch, then retries
     * [bazelBlock] exactly once - a second failure is allowed to propagate rather than looping
     * forever. The `!isOutOfDate && !isSuccess` branch is a distinct, unrecoverable failure (nothing
     * to do with pinning) and fails immediately without retry.
     */
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
                val (_, retryExecResult) = bazelBlock()
                retryExecResult.assertNormalExitValue()
            }

            !outputStream.isOutOfDate && !execResult.isSuccess -> {
                throw RuntimeException("Bazel command failed")
            }
        }
    }

    private fun starlarkBoolean(value: Boolean): String =
        if (value) "True" else "False"
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

/**
 * Picks the artifact used to probe a repo's pin status in [DefaultArtifactPinner.shouldRunPinning].
 * Direct, non-overridden dependencies are preferred because they resolve to a target Bazel can
 * build standalone with `--nobuild` to surface rules_jvm_external's out-of-date signal; an
 * override-targeted or purely transitive dependency may not have a buildable target of its own, or
 * may mask staleness behind the override, giving a false negative for repin necessity. Falling back
 * to `.first()` guarantees a probe always exists even when every input is overridden.
 */
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
