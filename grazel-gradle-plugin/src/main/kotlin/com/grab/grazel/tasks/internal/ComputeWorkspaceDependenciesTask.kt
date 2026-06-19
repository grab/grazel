/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.grab.grazel.gradle.ConfigurationDataSource
import com.grab.grazel.gradle.MigrationChecker
import com.grab.grazel.gradle.dependencies.AggregatedDependencyResolver
import com.grab.grazel.gradle.dependencies.AggregatedDependencyRoot
import com.grab.grazel.gradle.dependencies.AggregatedDependencyRootMetadata
import com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependencies
import com.grab.grazel.gradle.dependencies.DefaultDependencyGraphsService
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.variant.AndroidVariantDataSource
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.util.fromJson
import com.grab.grazel.util.GradleProvider
import com.grab.grazel.util.Json
import com.grab.grazel.util.logHeap
import com.grab.grazel.util.writeJson
import dagger.Lazy
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

@CacheableTask
internal abstract class ComputeWorkspaceDependenciesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val declaredDependencyMetadata: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kspDependencies: RegularFileProperty

    @get:Input
    abstract val workspaceDependencyRootComponents: ListProperty<ResolvedComponentResult>

    @get:Input
    abstract val workspaceDependencyRootMetadataJsons: ListProperty<String>

    @get:Internal
    abstract val dependencyResolutionService: Property<DefaultDependencyResolutionService>

    @get:OutputFile
    abstract val workspaceDependencies: RegularFileProperty

    init {
        group = GRAZEL_TASK_GROUP
        description = "Computes external maven dependencies for bazel"
    }

    @TaskAction
    fun action() {
        logger.logHeap("ComputeWorkspaceDeps:start")
        val rootComponents = workspaceDependencyRootComponents.get()
        val rootMetadata = workspaceDependencyRootMetadataJsons.get().map { metadataJson ->
            Json.decodeFromString<AggregatedDependencyRootMetadata>(metadataJson)
        }
        check(rootComponents.size == rootMetadata.size) {
            "Workspace dependency root component count (${rootComponents.size}) does not match " +
                "metadata count (${rootMetadata.size})"
        }

        val resolver = AggregatedDependencyResolver(
            rootProject = project.rootProject,
            declaredDependencyMetadata = fromJson<DeclaredDependencyMetadata>(
                declaredDependencyMetadata.get()
            ),
            precomputedKspDependencies = fromJson<ResolveDependenciesResult>(
                kspDependencies.get()
            ).dependencies.getOrDefault(KSP.name, emptySet()),
            workspaceDependencyRoots = rootComponents.zip(rootMetadata)
                .map { (root, metadata) -> AggregatedDependencyRoot(root, metadata) }
        )
        val result = ComputeWorkspaceDependencies().computeFromResults(resolver.resolve())

        logger.logHeap("ComputeWorkspaceDeps:computed")
        runBlocking {
            val populateCache = async(Dispatchers.Default) {
                dependencyResolutionService.get().populateCache(result)
            }
            val writeResult = async(Dispatchers.IO) {
                writeJson(result, workspaceDependencies.get())
            }
            awaitAll<Any>(populateCache, writeResult)
        }
        logger.logHeap("ComputeWorkspaceDeps:done")
    }

    companion object {
        private const val TASK_NAME = "computeWorkspaceDependencies"
        internal fun register(
            rootProject: Project,
            variantBuilderProvider: Lazy<VariantBuilder>,
            migrationChecker: Lazy<MigrationChecker>,
            dependencyResolutionService: GradleProvider<DefaultDependencyResolutionService>,
            dependencyGraphsService: GradleProvider<DefaultDependencyGraphsService>,
            dependenciesDataSource: Lazy<DependenciesDataSource>,
            configurationDataSource: Lazy<ConfigurationDataSource>,
            androidVariantDataSource: Lazy<AndroidVariantDataSource>,
        ): TaskProvider<ComputeWorkspaceDependenciesTask> {
            dependencyGraphsService.get().configure(
                rootProject = rootProject,
                dependenciesDataSource = dependenciesDataSource.get(),
                configurationDataSource = configurationDataSource.get(),
                androidVariantDataSource = androidVariantDataSource.get()
            )

            val computeTask = rootProject.tasks
                .register<ComputeWorkspaceDependenciesTask>(TASK_NAME) {
                    workspaceDependencies.set(
                        rootProject.layout.buildDirectory.file("grazel/dependencies.json")
                    )
                    this.dependencyResolutionService.set(dependencyResolutionService)
                    workspaceDependencyRootComponents.convention(emptyList())
                    workspaceDependencyRootMetadataJsons.convention(emptyList())
                }
            WorkspaceDependencyInputsRegistrar.register(
                rootProject = rootProject,
                variantBuilderProvider = variantBuilderProvider,
                migrationChecker = migrationChecker,
                computeTask = computeTask
            )
            return computeTask
        }
    }
}
