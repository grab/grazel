/*
 * Copyright 2026 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.grab.grazel.extension.DeclaredDependencyMetadataAggregationMode
import com.grab.grazel.gradle.MigrationChecker
import com.grab.grazel.gradle.dependencies.DeclaredProjectMetadataPlanner
import com.grab.grazel.gradle.dependencies.WorkspaceDependencyRootInputPlanner
import com.grab.grazel.gradle.dependencies.rootKey
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.gradle.variant.WorkspaceKspProcessorClasspathPlanner
import dagger.Lazy
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import java.util.concurrent.ConcurrentHashMap

internal object WorkspaceDependencyInputsRegistrar {
    /**
     * Wires the KSP, workspace-root-metadata, resolve, compute and (optionally) pin-maven-
     * artifacts tasks together. `computeTask` only needs `resolveWorkspaceDependenciesTask`'s
     * output and is configured eagerly, right after registration. The remaining tasks - KSP,
     * workspace-root-metadata, resolve, declared-dependency-metadata and (optionally)
     * pin-maven-artifacts - have their per-project inputs (root components, KSP classpath inputs,
     * declared-metadata sources, etc.) deliberately deferred to inside a `gradle.projectsEvaluated`
     * callback. Variant and dependency-declaration data is only complete once every subproject has
     * finished evaluating, so planning ([WorkspaceDependencyRootInputPlanner],
     * [WorkspaceKspProcessorClasspathPlanner], [DeclaredProjectMetadataPlanner]) must happen there
     * rather than at registration time. Those task providers configured inside that callback
     * depend on each other only through these deferred inputs, so their relative configuration
     * order there is a load-bearing, non-obvious invariant - reordering or moving any of them
     * outside `projectsEvaluated` would silently configure them against incomplete
     * variant/dependency data.
     */
    fun register(
        rootProject: Project,
        variantBuilderProvider: Lazy<VariantBuilder>,
        migrationChecker: Lazy<MigrationChecker>,
        declaredDependencyMetadataAggregationMode: Provider<DeclaredDependencyMetadataAggregationMode>,
        computeTask: TaskProvider<ComputeWorkspaceDependenciesTask>,
        pinMavenArtifactsTask: TaskProvider<PinMavenArtifactsTask>? = null,
        dataBindingMetaDataTask: TaskProvider<AndroidDatabindingMetaDataTask>? = null,
    ) {
        val variantsByProject = collectVariantsByProject(
            rootProject = rootProject,
            variantBuilder = variantBuilderProvider.get()
        )

        val declaredDependencyMetadataTask = DeclaredDependencyMetadataTasks.register(
            rootProject = rootProject
        )
        val kspProcessorDependenciesTask = CollectKspProcessorDependenciesTask.register(
            rootProject = rootProject
        )
        val workspaceRootMetadataTask = CollectWorkspaceDependencyRootMetadataTask.register(rootProject)
        val resolveWorkspaceDependenciesTask = ResolveWorkspaceDependenciesTask.register(rootProject)

        resolveWorkspaceDependenciesTask.configure {
            kspDependencies.set(
                kspProcessorDependenciesTask.flatMap { it.kspDependencies }
            )
            workspaceDependencyRootMetadata.set(
                workspaceRootMetadataTask.flatMap { it.workspaceDependencyRootMetadata }
            )
            dependsOn(kspProcessorDependenciesTask)
            dependsOn(workspaceRootMetadataTask)
        }
        computeTask.configure {
            workspaceDependencyResults.set(
                resolveWorkspaceDependenciesTask.flatMap { it.workspaceDependencyResults }
            )
            dependsOn(resolveWorkspaceDependenciesTask)
        }

        rootProject.gradle.projectsEvaluated {
            val migratableProjects = migratableProjects(
                rootProject = rootProject,
                migrationChecker = migrationChecker.get()
            )
            val rootInputs = WorkspaceDependencyRootInputPlanner.plan(
                migratableProjects = migratableProjects,
                variantsByProject = variantsByProject
            )
            val kspProcessorInputs = WorkspaceKspProcessorClasspathPlanner.plan(
                migratableProjects = migratableProjects,
                variantsByProject = variantsByProject
            )
            val declaredMetadataSources = DeclaredProjectMetadataPlanner.plan(
                projects = migratableProjects,
                variantsByProject = variantsByProject
            )
            kspProcessorDependenciesTask.configure {
                kspProcessorInputs.forEach { input ->
                    CollectKspProcessorDependenciesTask.addKspProcessorClasspathInput(
                        task = this,
                        input = input
                    )
                }
            }
            resolveWorkspaceDependenciesTask.configure {
                // workspaceDependencyRootKeys and workspaceDependencyRootComponents are
                // index-aligned BY CONSTRUCTION of this single loop — every add() pair below comes
                // from the same rootInput in the same iteration. This alignment must never be
                // relied upon across any other configure block; do not wire either property
                // anywhere else.
                rootInputs.forEach { rootInput ->
                    workspaceDependencyRootKeys.add(rootInput.rootKey())
                    workspaceDependencyRootComponents.add(
                        rootInput.configuration.incoming.resolutionResult.rootComponent
                    )
                }
            }
            workspaceRootMetadataTask.configure {
                rootInputs.forEach { rootInput ->
                    workspaceDependencyRootMetadataItems.add(
                        rootProject.objects
                            .newInstance(WorkspaceDependencyRootMetadataInput::class.java)
                            .apply {
                                setMetadata(rootProject.provider { rootInput.toMetadata() })
                            }
                    )
                }
            }
            pinMavenArtifactsTask?.configure {
                rootInputs.forEach { rootInput ->
                    localMavenResolutionRootConfigurations.add(rootInput.configuration)
                }
            }
            dataBindingMetaDataTask?.configure {
                rootInputs.forEach { rootInput ->
                    AndroidDatabindingMetaDataTask.addDatabindingAarArtifacts(
                        task = this,
                        rootInput = rootInput
                    )
                }
            }
            val mode = declaredDependencyMetadataAggregationMode.get()
            declaredDependencyMetadataTask.configureSingleTask(declaredMetadataSources)
            if (mode == DeclaredDependencyMetadataAggregationMode.PROJECT_TASK_FANOUT) {
                declaredDependencyMetadataTask.configureProjectTaskFanout(
                    metadataSources = declaredMetadataSources
                )
            }
            val declaredMetadataProducer = declaredDependencyMetadataTask.forMode(mode)
            resolveWorkspaceDependenciesTask.configure {
                declaredDependencyMetadata.set(declaredMetadataProducer.declaredDependencyMetadata)
                dependsOn(declaredMetadataProducer.producerTask)
            }
        }
    }

    private fun collectVariantsByProject(
        rootProject: Project,
        variantBuilder: VariantBuilder
    ): Map<Project, MutableSet<Variant<*>>> {
        val variantsByProject = ConcurrentHashMap<Project, MutableSet<Variant<*>>>()
        rootProject.subprojects.forEach { project ->
            try {
                variantBuilder.onVariants(project) { variant ->
                    variantsByProject
                        .computeIfAbsent(project) { linkedSetOf() }
                        .add(variant)
                }
            } catch (e: Exception) {
                rootProject.logger.warn(
                    "Grazel: Failed to register variant callbacks for ${project.path}: ${e.message}"
                )
            }
        }
        return variantsByProject
    }

    private fun migratableProjects(
        rootProject: Project,
        migrationChecker: MigrationChecker
    ): List<Project> {
        return rootProject.subprojects
            .filter { project -> migrationChecker.canMigrate(project) }
            .sortedBy { project -> project.path }
    }

}
