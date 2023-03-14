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

import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.bazel.exec.bazelCommand
import com.grab.grazel.util.BUILDIFIER
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

abstract class GenerateBuildifierScriptTask : DefaultTask() {

    @get:OutputFile
    abstract val buildifierScript: RegularFileProperty

    init {
        // This task is supposed to run alawys as the generated buildifier script does not change
        // even when buildifier version was changed.
        // We are pushing the responsibility to bazel to determine if it is in fact up-to-date or not
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun action() {
        project.bazelCommand(
            "run",
            "@grab_bazel_common//:buildifier",
            "--script_path=${buildifierScript.get().asFile.absolutePath}"
        )
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
            buildifierScript.set(project.layout.buildDirectory.file(BUILDIFIER))

            configureAction(this)
        }
    }
}
