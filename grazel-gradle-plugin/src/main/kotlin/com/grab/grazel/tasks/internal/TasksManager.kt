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
        grazelComponent.dependencyGraphsService().get().configure(
            rootProject = rootProject,
            dependenciesDataSource = grazelComponent.dependenciesDataSource().get(),
            configurationDataSource = grazelComponent.configurationDataSource().get(),
            androidVariantDataSource = grazelComponent.androidVariantDataSource().get()
        )

        val computeWorkspaceDependenciesTask = ComputeWorkspaceDependenciesTask.register(
            rootProject = rootProject,
            dependencyResolutionService = grazelComponent.dependencyResolutionService()
        )

        WorkspaceDependencyInputsRegistrar.register(
            rootProject = rootProject,
            variantBuilderProvider = grazelComponent.variantBuilder(),
            migrationChecker = grazelComponent.migrationChecker(),
            computeTask = computeWorkspaceDependenciesTask
        )

        val collectWorkspaceTargetTagPlanTask = CollectWorkspaceTargetTagPlanTask.register(
            rootProject = rootProject,
            grazelComponent = grazelComponent
        ) {
            workspaceDependencies.set(computeWorkspaceDependenciesTask.flatMap { it.workspaceDependencies })
            dependsOn(computeWorkspaceDependenciesTask)
        }

        val computeWorkspacePlanTask = ComputeWorkspacePlanTask.register(
            rootProject = rootProject,
            workspacePlanService = grazelComponent.workspacePlanService()
        ) {
            workspaceDependencies.set(computeWorkspaceDependenciesTask.flatMap { it.workspaceDependencies })
            targetTagPlan.set(collectWorkspaceTargetTagPlanTask.flatMap { it.targetTagPlan })
            configuredOverrideTargets.set(
                grazelComponent.extension().rules.mavenInstall.overrideTargetLabels
            )
            dependsOn(collectWorkspaceTargetTagPlanTask)
        }

        // Analyze variant compression opportunities in topological order
        val analyzeVariantCompressionTask = AnalyzeVariantCompressionTask.register(
            rootProject = rootProject,
            grazelComponent = grazelComponent
        ) {
            workspaceDependencies.set(computeWorkspaceDependenciesTask.flatMap { it.workspaceDependencies })
            workspacePlan.set(computeWorkspacePlanTask.flatMap { it.workspacePlan })
            dependsOn(computeWorkspacePlanTask)
        }

        val collectTargetMavenRepoReferencesTask = CollectTargetMavenRepoReferencesTask.register(
            rootProject = rootProject,
            grazelComponent = grazelComponent
        ) {
            workspaceDependencies.set(computeWorkspaceDependenciesTask.flatMap { it.workspaceDependencies })
            workspacePlan.set(computeWorkspacePlanTask.flatMap { it.workspacePlan })
            dependencyResolutionService.set(grazelComponent.dependencyResolutionService())
            dependsOn(analyzeVariantCompressionTask)
        }

        val finalizeWorkspacePlanTask = FinalizeWorkspacePlanTask.register(
            rootProject = rootProject,
            workspacePlanService = grazelComponent.workspacePlanService()
        ) {
            workspacePlan.set(computeWorkspacePlanTask.flatMap { it.workspacePlan })
            targetMavenRepoReferences.set(
                collectTargetMavenRepoReferencesTask.flatMap { it.targetMavenRepoReferences }
            )
            dependsOn(collectTargetMavenRepoReferencesTask)
        }

        val dataBindingMetaDataTask = AndroidDatabindingMetaDataTask.register(
            rootProject,
            grazelComponent
        ) {
            dependsOn(computeWorkspaceDependenciesTask)
        }

        val generateDownloaderConfigTask = GenerateDownloaderConfigTask.register(
            rootProject,
            grazelComponent
        )

        // Post script generate task must run after scripts are generated
        val postScriptGenerateTask = PostScriptGenerateTask.register(rootProject, grazelComponent)

        val projectGenerateBazelScriptsTasks = rootProject.subprojects.map { project ->
            val generateBazelScriptsTask = GenerateBazelScriptsTask.register(
                project,
                grazelComponent
            ) {
                dependencyResolutionService.set(grazelComponent.dependencyResolutionService())
                workspaceDependencies.set(computeWorkspaceDependenciesTask.flatMap { it.workspaceDependencies })
                workspacePlan.set(computeWorkspacePlanTask.flatMap { it.workspacePlan })
                workspaceRenderPlan.set(finalizeWorkspacePlanTask.flatMap { it.workspaceRenderPlan })
                variantCompressionResults.set(analyzeVariantCompressionTask.flatMap { it.compressionResultsFile })
                dependsOn(finalizeWorkspacePlanTask)
            }

            // Post script generate task must run after project level tasks are generated
            postScriptGenerateTask.dependsOn(generateBazelScriptsTask)

            project to generateBazelScriptsTask
        }

        // Root bazel file generation materializes Maven repos from the finalized render plan.
        val rootGenerateBazelScriptsTasks = GenerateRootBazelScriptsTask.register(
            rootProject,
            grazelComponent
        ) {
            workspaceDependencies.set(computeWorkspaceDependenciesTask.flatMap { it.workspaceDependencies })
            workspacePlan.set(computeWorkspacePlanTask.flatMap { it.workspacePlan })
            workspaceRenderPlan.set(finalizeWorkspacePlanTask.flatMap { it.workspaceRenderPlan })
            dependencyResolutionService.set(grazelComponent.dependencyResolutionService())
            dependsOn(finalizeWorkspacePlanTask)
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
            workspaceFile.set(rootFormattingTasks.workspace.flatMap { it.outputFile })
            workspacePlan.set(computeWorkspacePlanTask.flatMap { it.workspacePlan })
            workspaceRenderPlan.set(finalizeWorkspacePlanTask.flatMap { it.workspaceRenderPlan })
        }

        // Project level Bazel file formatting tasks
        val projectBazelFormattingTasks = projectGenerateBazelScriptsTasks.map { (project, generateBazelScriptsTask) ->
            // Project level Bazel formatting depends on generation tasks
            FormatBazelFileTask.register(
                project = project,
                buildifierScriptProvider = buildifierScriptProvider,
            ) {
                inputFile.set(generateBazelScriptsTask.flatMap { it.buildBazel })
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
