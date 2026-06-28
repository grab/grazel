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

import com.grab.grazel.gradle.MigrationChecker
import com.grab.grazel.gradle.dependencies.WorkspaceDependencyRootInput
import com.grab.grazel.gradle.dependencies.WorkspaceDependencyRootInputPlanner
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.util.Json
import dagger.Lazy
import kotlinx.serialization.encodeToString
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import java.util.concurrent.ConcurrentHashMap

internal object WorkspaceDependencyInputsRegistrar {
    fun register(
        rootProject: Project,
        variantBuilderProvider: Lazy<VariantBuilder>,
        migrationChecker: Lazy<MigrationChecker>,
        computeTask: TaskProvider<ComputeWorkspaceDependenciesTask>
    ) {
        val declaredDependencyMetadataTask = CollectDeclaredDependencyMetadataTask.register(
            rootProject = rootProject,
            variantBuilderProvider = variantBuilderProvider,
            migrationChecker = migrationChecker
        )
        val kspProcessorDependenciesTask = CollectKspProcessorDependenciesTask.register(
            rootProject = rootProject,
            migrationChecker = migrationChecker
        )
        val resolveWorkspaceDependenciesTask = ResolveWorkspaceDependenciesTask.register(rootProject)

        resolveWorkspaceDependenciesTask.configure {
            declaredDependencyMetadata.set(
                declaredDependencyMetadataTask.flatMap { it.declaredDependencyMetadata }
            )
            kspDependencies.set(
                kspProcessorDependenciesTask.flatMap { it.kspDependencies }
            )
            dependsOn(declaredDependencyMetadataTask)
            dependsOn(kspProcessorDependenciesTask)
        }
        computeTask.configure {
            workspaceDependencyResults.set(
                resolveWorkspaceDependenciesTask.flatMap { it.workspaceDependencyResults }
            )
            dependsOn(resolveWorkspaceDependenciesTask)
        }

        val variantsByProject = collectVariantsByProject(
            rootProject = rootProject,
            variantBuilder = variantBuilderProvider.get()
        )

        rootProject.gradle.projectsEvaluated {
            resolveWorkspaceDependenciesTask.configure {
                wireRootComponents(
                    rootProject = rootProject,
                    migrationChecker = migrationChecker.get(),
                    variantsByProject = variantsByProject
                )
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

    private fun ResolveWorkspaceDependenciesTask.wireRootComponents(
        rootProject: Project,
        migrationChecker: MigrationChecker,
        variantsByProject: Map<Project, Iterable<Variant<*>>>
    ) {
        val migratableProjects = rootProject.subprojects
            .filter { project -> migrationChecker.canMigrate(project) }
            .sortedBy { project -> project.path }
        WorkspaceDependencyRootInputPlanner
            .plan(
                migratableProjects = migratableProjects,
                variantsByProject = variantsByProject
            )
            .forEach { rootInput ->
                addRoot(rootInput)
            }
    }

    private fun ResolveWorkspaceDependenciesTask.addRoot(rootInput: WorkspaceDependencyRootInput) {
        workspaceDependencyRootComponents.add(
            rootInput.configuration.incoming.resolutionResult.rootComponent
        )
        workspaceDependencyRootMetadataJsons.add(Json.encodeToString(rootInput.toMetadata()))
    }
}
