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
import com.grab.grazel.di.GrazelComponent
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.util.BAZEL_BUILD_ALL_TASK_NAME
import com.grab.grazel.util.BAZEL_CLEAN_TASK_NAME
import com.grab.grazel.util.BUILD_BAZEL
import com.grab.grazel.util.WORKSPACE
import com.grab.grazel.util.dependsOn
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import javax.inject.Inject

internal const val GRAZEL_TASK_GROUP = "bazel"

/**
 * [TaskManager] configures relationships and input between various tasks that Grazel registers
 *
 * @param rootProject  The root gradle project instance
 */
internal class TaskManager
@Inject
constructor(
    @param:RootProject private val rootProject: Project,
    private val grazelComponent: GrazelComponent
) {

    /**
     * Register and configure task dependencies for generation, formatting and `migrateToBazel`.
     *
     * A <-- B means B depends on A
     *
     * * Root Scripts generation <-- Project level generation
     * * Root Scripts generation <-- Buildifier Script generation
     * * Root Scripts generation <-- Android data binding metadata generation (if enabled)
     * * Project level generation <-- Project level formatting
     * * Project level generation <-- Post script generate task
     * * Buildifier Script generation <-- Root formatting
     * * Buildifier Script generation <-- Project level formatting
     * * Root formatting <-- Formatting
     * * Project level formatting <-- Formatting
     * * Formatting <-- Migrate To Bazel
     * * Post script generate task <-- Migrate To Bazel
     *
     * See [Task Graph](https://grab.github.io/Grazel/gradle_tasks/#task-graph)
     */
    fun configTasks() {
        ResolveDependenciesTask.register(rootProject, grazelComponent)

        // Root bazel file generation task that should run at the start of migration
        val rootGenerateBazelScriptsTasks = GenerateRootBazelScriptsTask.register(
            rootProject,
            grazelComponent
        )

        val dataBindingMetaDataTask = AndroidDatabindingMetaDataTask
            .register(rootProject, grazelComponent) {
                dependsOn(rootGenerateBazelScriptsTasks)
            }

        val generateBuildifierScriptTask = GenerateBuildifierScriptTask.register(
            rootProject
        ) {
            dependsOn(rootGenerateBazelScriptsTasks)
        }

        val buildifierScriptProvider = generateBuildifierScriptTask.flatMap { it.buildifierScript }

        // Root formatting task depends on sub project formatting and root generation task
        val formatBazelFilesTask = FormatBazelFileTask.register(
            project = rootProject,
            buildifierScriptProvider = buildifierScriptProvider,
        ) {
            dependsOn(rootGenerateBazelScriptsTasks)
        }

        // Post script generate task must run after scripts are generated
        val postScriptGenerateTask = PostScriptGenerateTask.register(rootProject, grazelComponent)

        // Project level Bazel file formatting tasks
        val projectBazelFormattingTasks = rootProject.subprojects.map { project ->
            // Project level Bazel generation tasks
            val generateBazelScriptsTasks = GenerateBazelScriptsTask
                .register(project, grazelComponent) {
                    dependsOn(rootGenerateBazelScriptsTasks)
                }

            // Post script generate task must run after project level tasks are generated
            postScriptGenerateTask.dependsOn(generateBazelScriptsTasks)

            // Project level Bazel formatting depends on generation tasks
            FormatBazelFileTask.register(
                project = project,
                buildifierScriptProvider = buildifierScriptProvider,
            ) {
                dependsOn(generateBazelScriptsTasks)
            }
        }

        formatBazelFilesTask.dependsOn(projectBazelFormattingTasks)

        val migrateTask = migrateToBazelTask().apply {
            dependsOn(formatBazelFilesTask, postScriptGenerateTask)
            configure {
                // Inside a configure block since GrazelExtension won't be configured yet if
                // we write it as part of plugin application and all extension value would
                // have default value instead of user configured value.
                if (grazelComponent.extension().android.features.dataBindingMetaData) {
                    dependsOn(dataBindingMetaDataTask)
                }
            }
        }

        bazelBuildAllTask().dependsOn(migrateTask)

        registerBazelCleanTask()
    }


    private fun migrateToBazelTask(): TaskProvider<Task> {
        return rootProject.tasks.register("migrateToBazel") {
            group = GRAZEL_TASK_GROUP
            description = "Generates Bazel build files for this project"
        }
    }

    private fun bazelBuildAllTask(): TaskProvider<Task> {
        return rootProject.tasks.register(BAZEL_BUILD_ALL_TASK_NAME) {
            group = GRAZEL_TASK_GROUP
            description = "Do a Bazel build from all generated build files"
            doLast {
                project.bazelCommand("build", "//...")
            }
        }
    }

    private fun registerBazelCleanTask() {
        rootProject.run {
            tasks.register(BAZEL_CLEAN_TASK_NAME) {
                group = GRAZEL_TASK_GROUP
                description = "Clean Bazel artifacts and all generated bazel files"
                doLast {
                    delete(fileTree(projectDir).matching {
                        include("**/$BUILD_BAZEL")
                        include("**/$WORKSPACE")
                    })
                }
            }
        }
    }
}
