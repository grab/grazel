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
import com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependencies
import com.grab.grazel.gradle.dependencies.DefaultDependencyGraphsService
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.variant.AndroidVariantDataSource
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.util.GradleProvider
import com.grab.grazel.util.writeJson
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

@CacheableTask
internal abstract class ComputeWorkspaceDependenciesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compileDependenciesJsons: ListProperty<RegularFile>

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
        val result = ComputeWorkspaceDependencies().compute(compileDependenciesJsons.get())
        runBlocking {
            val populateCache = async(Dispatchers.Default) {
                dependencyResolutionService.get().populateCache(result)
            }
            val writeResult = async(Dispatchers.IO) {
                writeJson(result, workspaceDependencies.get())
            }
            awaitAll<Any>(populateCache, writeResult)
        }
    }

    companion object {
        private const val TASK_NAME = "computeWorkspaceDependencies"
        internal fun register(
            rootProject: Project,
            variantBuilderProvider: Lazy<VariantBuilder>,
            limitDependencyResolutionParallelism: Property<Boolean>,
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
                }
            ResolveVariantDependenciesTask.register(
                rootProject,
                variantBuilderProvider,
                limitDependencyResolutionParallelism,
            ) { taskProvider ->
                computeTask.configure {
                    compileDependenciesJsons.add(taskProvider.flatMap { it.resolvedDependencies })
                }
            }
            return computeTask
        }
    }
}
