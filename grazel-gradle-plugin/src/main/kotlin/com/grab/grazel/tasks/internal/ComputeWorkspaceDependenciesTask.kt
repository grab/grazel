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

import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.allDependencies
import com.grab.grazel.gradle.dependencies.model.versionInfo
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.VariantBuilder
import dagger.Lazy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import java.io.File
import java.util.stream.Collectors
import java.util.stream.Collectors.flatMapping
import java.util.stream.Collectors.groupingByConcurrent
import kotlin.streams.toList

@CacheableTask
abstract class ComputeWorkspaceDependenciesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compileDependenciesJsons: ListProperty<RegularFile>

    @get:OutputFile
    abstract val mergedDependencies: RegularFileProperty

    init {
        group = GRAZEL_TASK_GROUP
        description = "Computes external maven dependencies for bazel"
    }

    @TaskAction
    fun action() {
        val flattenClasspath = compileDependenciesJsons.get()
            .parallelStream()
            .map(ResolveDependenciesResult::fromJson)
            .collect(
                // Group variantName to dependencies
                groupingByConcurrent(
                    ResolveDependenciesResult::variantName,
                    // Extract compile classpath and flatten it by including the transitive closure
                    flatMapping(
                        { resolvedDependency ->
                            resolvedDependency
                                .dependencies
                                .getValue("compile")
                                .stream()
                                .flatMap { it.allDependencies.stream() }
                                .parallel()
                        },
                        // To find the max version, need to group by their shortID
                        groupingByConcurrent(
                            ResolvedDependency::shortId,
                            // Once grouped, reduce it and only pick the highest version
                            Collectors.reducing(null) { old, new ->
                                when {
                                    old == null -> new
                                    new == null -> old
                                    else -> if (old.versionInfo > new.versionInfo) old else new
                                }
                            }
                        )
                    )
                )
            )

        val defaultClasspath = flattenClasspath.getValue(DEFAULT_VARIANT)

        val finalClasspath = flattenClasspath
            .entries
            .parallelStream()
            .filter { it.key != DEFAULT_VARIANT }
            .collect(
                Collectors.toConcurrentMap(
                    { it.key },
                    { (variantName, dependencies) ->
                        dependencies
                            .values
                            .stream()
                            .map { dependency ->
                                if (dependency!!.shortId in defaultClasspath) {
                                    // TODO(arun) Add override target and check for all parent classpaths
                                    println(dependency.shortId)
                                }
                                dependency
                            }.toList()
                    }
                )
            ).apply {
                put(DEFAULT_VARIANT, defaultClasspath.values.toList())
            }.filterValues { it.isNotEmpty() }

        mergedDependencies.asFile.get().writeText(Json.encodeToString(finalClasspath.toMap()))
    }

    companion object {
        private const val TASK_NAME = "computeWorkspaceDependencies"
        internal fun register(rootProject: Project, variantBuilderProvider: Lazy<VariantBuilder>) {
            val computeTask = rootProject.tasks
                .register<ComputeWorkspaceDependenciesTask>(TASK_NAME) {
                    mergedDependencies.set(
                        File(
                            rootProject.buildDir,
                            "grazel/mergedDependencies.json"
                        )
                    )
                }
            ResolveVariantDependenciesTask.register(
                rootProject,
                variantBuilderProvider
            ) { taskProvider ->
                computeTask.configure {
                    compileDependenciesJsons.add(taskProvider.flatMap { it.resolvedDependencies })
                }
            }
        }
    }
}