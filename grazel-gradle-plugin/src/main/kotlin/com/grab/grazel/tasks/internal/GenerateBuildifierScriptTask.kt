/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.tasks.internal

import com.grab.grazel.bazel.exec.bazelCommand
import com.grab.grazel.bazel.rules.GRAB_BAZEL_COMMON
import com.grab.grazel.di.GradleServices
import com.grab.grazel.di.GrazelComponent
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.migrate.dependencies.ArtifactPinner
import com.grab.grazel.migrate.dependencies.BazelLogParsingOutputStream
import com.grab.grazel.util.BUILDIFIER
import com.grab.grazel.util.startOperation
import dagger.Lazy
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.LogLevel.QUIET
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.UntrackedTask
import org.gradle.kotlin.dsl.register
import javax.inject.Inject

@UntrackedTask(because = "Caching implemented via Bazel")
internal open class GenerateBuildifierScriptTask
@Inject
constructor(
    private val artifactPinner: Lazy<ArtifactPinner>,
    private val gradleServices: GradleServices
) : DefaultTask() {

    @get:OutputFile
    val buildifierScript: RegularFileProperty = gradleServices.objectFactory.fileProperty()

    @TaskAction
    fun action() {
        artifactPinner.get().ensureSafeToRun(logger, gradleServices) {
            val progress = gradleServices
                .progressLoggerFactory
                .startOperation("Setting up buildifier")
            val outputStream = BazelLogParsingOutputStream(
                logger = logger,
                level = QUIET,
                progressLogger = progress,
                logOutput = true
            )
            val execResult = gradleServices.execOperations.bazelCommand(
                logger = logger,
                "run",
                "@$GRAB_BAZEL_COMMON//:buildifier",
                "--script_path=${buildifierScript.get().asFile.absolutePath}",
                errorOutputStream = outputStream,
                ignoreExit = true
            )
            progress.completed()
            outputStream to execResult
        }
    }

    companion object {
        private const val TASK_NAME = "generateBuildifierScript"

        fun register(
            @RootProject project: Project,
            grazelComponent: GrazelComponent,
            configureAction: GenerateBuildifierScriptTask.() -> Unit = {},
        ): TaskProvider<GenerateBuildifierScriptTask> {
            val gradleServices = GradleServices.from(project)
            return project.tasks.register<GenerateBuildifierScriptTask>(
                TASK_NAME,
                grazelComponent.artifactPinner(),
                gradleServices
            ).apply {
                configure {
                    description = "Generates buildifier executable script"
                    group = GRAZEL_TASK_GROUP
                    val buildDirectory = project.layout.buildDirectory
                    buildifierScript.convention(buildDirectory.file("grazel/$BUILDIFIER"))
                    configureAction(this)
                }
            }
        }
    }
}
