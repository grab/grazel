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
 * @param rootProject The root gradle project instance
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
     * See [Task Graph](https://grab.github.io/Grazel/gradle_tasks/#task-graph)
     */
    fun configTasks() {
        val computeWorkspaceDependenciesTask = ComputeWorkspaceDependenciesTask.register(
            rootProject = rootProject,
            dependencyResolutionService = grazelComponent.dependencyResolutionService(),
            variantBuilderProvider = grazelComponent.variantBuilder(),
            limitDependencyResolutionParallelism = grazelComponent.extension().experiments.limitDependencyResolutionParallelism
        )
        // Root bazel file generation task that should run at the start of migration
        val rootGenerateBazelScriptsTasks = GenerateRootBazelScriptsTask.register(
            rootProject,
            grazelComponent
        ) {
            workspaceDependencies.set(computeWorkspaceDependenciesTask.flatMap { it.workspaceDependencies })
            dependencyResolutionService.set(grazelComponent.dependencyResolutionService())
        }

        val dataBindingMetaDataTask = AndroidDatabindingMetaDataTask.register(
            rootProject,
            grazelComponent
        ) {
            dependsOn(computeWorkspaceDependenciesTask)
        }

        val generateBuildifierScriptTask = GenerateBuildifierScriptTask.register(
            rootProject,
            grazelComponent
        ) {
            dependsOn(rootGenerateBazelScriptsTasks)
        }

        val buildifierScriptProvider = generateBuildifierScriptTask.flatMap { it.buildifierScript }

        // Root formatting tasks with generated workspace and build bazel files
        val rootFormattingTasks = FormatBazelFileTask.registerRootFormattingTasks(
            rootProject,
            buildifierScriptProvider,
            workspaceFormattingTask = {
                inputFile.set(rootGenerateBazelScriptsTasks.flatMap { it.workspaceFile })
            },
            rootBuildBazelTask = {
                inputFile.set(rootGenerateBazelScriptsTasks.flatMap { it.buildBazel })
            }
        )

        val pinArtifactsTask = PinMavenArtifactsTask.register(rootProject, grazelComponent) {
            dependencyResolutionService.set(grazelComponent.dependencyResolutionService())
            workspaceFile.set(rootFormattingTasks.workspace.flatMap { it.outputFile })
            workspaceDependencies.set(computeWorkspaceDependenciesTask.flatMap { it.workspaceDependencies })
        }

        val generateDownloaderConfigTask = GenerateDownloaderConfigTask.register(
            rootProject,
            grazelComponent
        )

        // Post script generate task must run after scripts are generated
        val postScriptGenerateTask = PostScriptGenerateTask.register(rootProject, grazelComponent)

        // Project level Bazel file formatting tasks
        val projectBazelFormattingTasks = rootProject.subprojects.map { project ->
            // Project level Bazel generation tasks
            val generateBazelScriptsTasks = GenerateBazelScriptsTask.register(
                project,
                grazelComponent
            ) {
                dependencyResolutionService.set(grazelComponent.dependencyResolutionService())
                workspaceDependencies.set(computeWorkspaceDependenciesTask.flatMap { it.workspaceDependencies })
            }

            // Post script generate task must run after project level tasks are generated
            postScriptGenerateTask.dependsOn(generateBazelScriptsTasks)

            // Project level Bazel formatting depends on generation tasks
            FormatBazelFileTask.register(
                project = project,
                buildifierScriptProvider = buildifierScriptProvider,
            ) {
                inputFile.set(generateBazelScriptsTasks.flatMap { it.buildBazel })
            }
        }

        val migrateTask = migrateToBazelTask().apply {
            dependsOn(postScriptGenerateTask)
            dependsOn(rootFormattingTasks.all)
            dependsOn(projectBazelFormattingTasks)
            configure {
                // Inside a configure block since GrazelExtension won't be configured yet and if
                // we write it as part of plugin application and all extension value would
                // have default value instead of user configured value.
                if (grazelComponent.extension().android.features.dataBindingMetaData) {
                    dependsOn(dataBindingMetaDataTask)
                }
                if (grazelComponent.extension().rules.mavenInstall.artifactPinning.enabled.get()) {
                    dependsOn(pinArtifactsTask)
                    dependsOn(generateDownloaderConfigTask)
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
