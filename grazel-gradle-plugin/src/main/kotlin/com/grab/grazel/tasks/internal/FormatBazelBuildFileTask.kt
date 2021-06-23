/*
 * Copyright 2021 Grabtaxi Holdings PTE LTE (GRAB)
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

import com.grab.grazel.util.BUILD_BAZEL
import com.grab.grazel.util.WORKSPACE
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import java.io.File

private const val FORMAT_BAZEL_FILE_TASK = "formatBazelScripts"
private const val FORMAT_BUILD_BAZEL_FILE_TASK = "formatBuildBazel"
private const val FORMAT_WORK_SPACE_FILE_TASK = "formatWorkSpace"

open class FormatBazelFileTask : Exec() {
    @get:OutputFile
    var bazelFile: File = File(project.projectDir, BUILD_BAZEL)

    override fun exec() = if (bazelFile.exists()) {
        // set command here due to this issue: https://discuss.gradle.org/t/dofirst-does-not-execute-first-but-only-after-task-execution/28129/4
        setupCommand()
        super.exec()
    } else {
        // project.logger.quiet("The build.bazel file in ${project.name} is not exist")
    }

    private fun setupCommand() {
        commandLine = listOf("buildifier", bazelFile.absolutePath)
    }

    companion object {
        private const val TASK_DESCRIPTION = "Format Bazel build files"

        /**
         * Register formatting task on the given project and provide callbacks to configure it.
         *
         * Based on the project type i.e whether root or subproject, register the correct formatting task and provide
         * callbacks to configure it.
         *
         * @param project The project instance to register for
         * @param configureAction Callback to configure the registered task. Can be called multiple times for all registered
         *                        tasks
         * @return The created provider for this task.
         */
        fun register(
            project: Project,
            configureAction: Task.() -> Unit
        ): TaskProvider<out Task> {
            val rootProject = project.rootProject
            if (project == rootProject) {
                // Format work space
                val formatWorkspace = rootProject.tasks.register<FormatBazelFileTask>(FORMAT_WORK_SPACE_FILE_TASK) {
                    bazelFile = File(rootProject.projectDir, WORKSPACE)
                    group = GRAZEL_TASK_GROUP
                    description = "Format $WORKSPACE file"

                    configureAction(this)
                }
                // Format build.bazel
                val formatBuildBazel = rootProject.tasks.register<FormatBazelFileTask>(FORMAT_BUILD_BAZEL_FILE_TASK) {
                    group = GRAZEL_TASK_GROUP
                    description = "Format $BUILD_BAZEL file"

                    configureAction(this)
                }
                // Aggregating task to depend on above
                return rootProject.tasks.register(FORMAT_BAZEL_FILE_TASK) {
                    group = GRAZEL_TASK_GROUP
                    description = TASK_DESCRIPTION
                    dependsOn(formatWorkspace, formatBuildBazel)
                }
            } else {
                return project.tasks.register<FormatBazelFileTask>(FORMAT_BAZEL_FILE_TASK) {
                    group = GRAZEL_TASK_GROUP
                    description = TASK_DESCRIPTION

                    configureAction(this)
                }
            }
        }
    }
}