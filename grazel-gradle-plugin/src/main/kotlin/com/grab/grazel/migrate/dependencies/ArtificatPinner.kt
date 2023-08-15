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

import com.grab.grazel.bazel.exec.bazelCommand
import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.di.GradleServices
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.util.ansiCyan
import com.grab.grazel.util.ansiGreen
import com.grab.grazel.util.isSuccess
import com.grab.grazel.util.startOperation
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.LogLevel.QUIET
import org.gradle.api.logging.Logger
import org.gradle.internal.logging.progress.ProgressLogger
import org.gradle.process.ExecOperations
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal interface ArtifactPinner {
    fun pinArtifacts(
        workspaceFile: File,
        workspaceDependencies: WorkspaceDependencies,
        gradleServices: GradleServices,
        logger: Logger
    ): Boolean
}

@Singleton
internal class DefaultArtifactPinner
@Inject
constructor() : ArtifactPinner {

    private fun pin(workspaceFile: File) {
        workspaceFile.writeText(
            workspaceFile.readText().replace(
                "#maven_install_json ",
                "maven_install_json "
            )
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
        workspaceDependencies: WorkspaceDependencies,
        gradleServices: GradleServices,
        parentProgress: ProgressLogger,
        logger: Logger,
        logOutput: Boolean = false
    ): Boolean {
        val progressLoggerFactory = gradleServices.progressLoggerFactory
        val progress = progressLoggerFactory.startOperation<DefaultArtifactPinner>(
            "Checking pin status",
            parentProgress
        )
        logger.quiet("Checking if artifacts should be repinned".ansiCyan)
        val mavenInstallJsonMissing = workspaceFile.useLines { lines ->
            lines.any { line -> line.contains("#maven_install_json") }
        }
        if (mavenInstallJsonMissing) {
            // If we detect maven install json is missing for any repo, we run pinning again
            // to regenerate the file.
            return true
        } else {
            failWhenOutOfDate(workspaceFile, true)
            return workspaceDependencies.result.any { (repo, deps) ->
                // Build any dependency with nobuild and check it fails due to maven_install.json
                // being out of date
                val dep = deps.first()
                val (group, name) = dep.shortId.split(":")
                val mavenRepo = repo.toMavenRepoName()

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
                outputStream.isOutOfDate
            }.also {
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

    override fun pinArtifacts(
        workspaceFile: File,
        workspaceDependencies: WorkspaceDependencies,
        gradleServices: GradleServices,
        logger: Logger,
    ): Boolean {
        val progressLoggerFactory = gradleServices.progressLoggerFactory

        val progressLogger = progressLoggerFactory.startOperation("Pin maven artifacts")

        val shouldRun = shouldRunPinning(
            workspaceFile,
            workspaceDependencies,
            gradleServices,
            progressLogger,
            logger
        )
        val layout = gradleServices.layout

        if (shouldRun) {
            logger.quiet("Repinning all artifacts".ansiCyan)
            val pinScripts = workspaceDependencies.result.mapValues { (repo, _) ->
                val mavenRepoName = repo.toMavenRepoName()
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
            val isSuccess = pinScripts.all { it.second.isSuccess }
            if (isSuccess) {
                pin(workspaceFile)
                pinScripts.forEach { (script, _) ->
                    gradleServices.workerExecutor
                        .noIsolation()
                        .submit(PinningWorkAction::class.java) { pinScript.set(script) }
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
}

internal open class PinningWorkAction
@Inject
constructor(
    private val execOperations: ExecOperations
) : WorkAction<PinningWorkAction.Parameters> {

    interface Parameters : WorkParameters {
        val pinScript: RegularFileProperty
    }

    @Inject
    override fun getParameters(): Parameters {
        throw UnsupportedOperationException("not implemented")
    }

    override fun execute() {
        execOperations.exec {
            commandLine = listOf(
                parameters.pinScript.get().asFile.absolutePath,
            )
        }
    }
}
