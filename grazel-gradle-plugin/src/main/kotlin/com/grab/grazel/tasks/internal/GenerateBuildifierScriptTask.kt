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
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.migrate.dependencies.BazelLogParsingOutputStream
import com.grab.grazel.util.BUILDIFIER
import com.grab.grazel.util.startOperation
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.LogLevel.QUIET
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.internal.logging.progress.ProgressLoggerFactory
import org.gradle.kotlin.dsl.register
import org.gradle.process.ExecOperations
import javax.inject.Inject

@UntrackedTask(because = "Caching implemented via Bazel")
internal open class GenerateBuildifierScriptTask
@Inject
constructor(
    objects: ObjectFactory,
    private val execOperations: ExecOperations,
    private val progressLoggerFactory: ProgressLoggerFactory
) : DefaultTask() {

    @get:OutputFile
    val buildifierScript: RegularFileProperty = objects.fileProperty()

    @TaskAction
    fun action() {
        val progress = progressLoggerFactory.startOperation("Setting up buildifier")
        val outputStream = BazelLogParsingOutputStream(
            logger = logger,
            level = QUIET,
            progressLogger = progress,
            logOutput = true
        )
        execOperations.bazelCommand(
            logger = logger,
            "run",
            "@grab_bazel_common//:buildifier",
            "--script_path=${buildifierScript.get().asFile.absolutePath}",
            errorOutputStream = outputStream,
        )
        progress.completed()
    }

    companion object {
        private const val TASK_NAME = "generateBuildifierScript"

        fun register(
            @RootProject project: Project,
            configureAction: GenerateBuildifierScriptTask.() -> Unit = {},
        ) = project.tasks.register<GenerateBuildifierScriptTask>(
            TASK_NAME,
        ) {
            description = "Generates buildifier executable script"
            group = GRAZEL_TASK_GROUP
            val buildDirectory = project.layout.buildDirectory
            buildifierScript.convention(buildDirectory.file("grazel/$BUILDIFIER"))
            configureAction(this)
        }
    }
}
