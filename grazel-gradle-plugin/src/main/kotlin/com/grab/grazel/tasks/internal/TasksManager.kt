/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.tasks.internal

import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.grab.grazel.di.GrazelComponent
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.hybrid.bazelCommand
import com.grab.grazel.util.BAZEL_BUILD_ALL_TASK_NAME
import com.grab.grazel.util.BAZEL_CLEAN_TASK_NAME
import com.grab.grazel.util.BUILD_BAZEL
import com.grab.grazel.util.WORKSPACE
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
internal class TaskManager @Inject constructor(
    @param:RootProject private val rootProject: Project,
    private val grazelComponent: GrazelComponent
) {

    /**
     * Register and configure task dependencies for generation, formatting and `migrateToBazel`.
     *
     * * Root Scripts generation <-- Project level generation
     * * Root Scripts generation <-- Root formatting
     * * Project level generation <-- Project level formatting
     * * Root formatting <-- Formatting
     * * Project level formatting <-- Formatting
     * * Formatting <-- Migrate To Bazel
     */
    fun configTasks() {
        // Root bazel file generation task that should run at the start of migration
        val rootGenerateBazelScriptsTasks = GenerateRootBazelScriptsTask.register(
            rootProject,
            grazelComponent
        )

        // Root formatting task depends on sub project formatting and root generation task
        val formatBazelFilesTask = FormatBazelFileTask.register(rootProject) {
            dependsOn(rootGenerateBazelScriptsTasks)
        }

        // Project level Bazel file formatting tasks
        val projectBazelFormattingTasks = rootProject.subprojects.map { project ->
            // Project level Bazel generation tasks
            val generateBazelScriptsTasks = GenerateBazelScriptsTask
                .register(project, grazelComponent) {
                    dependsOn(rootGenerateBazelScriptsTasks)
                }

            // Project level Bazel formatting depends on generation tasks
            FormatBazelFileTask.register(project) {
                dependsOn(generateBazelScriptsTasks)
            }
        }

        formatBazelFilesTask.dependsOn(projectBazelFormattingTasks)

        val migrateTask = migrateToBazelTask().apply { dependsOn(formatBazelFilesTask) }

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