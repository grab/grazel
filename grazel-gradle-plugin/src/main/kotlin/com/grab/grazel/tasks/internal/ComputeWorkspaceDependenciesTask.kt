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

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.gradle.dependencies.model.OverrideTarget
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
        // Parse all jsons parallely and compute the classpaths among all variants
        val classPaths = compileDependenciesJsons.get()
            .parallelStream()
            .map(ResolveDependenciesResult.Companion::fromJson)
            .collect(
                groupingByConcurrent(
                    ResolveDependenciesResult::variantName,
                    flatMapping(
                        { resolvedDependency ->
                            resolvedDependency
                                .dependencies
                                .getValue("compile")
                                .parallelStream()
                        },
                        groupingByConcurrent(ResolvedDependency::id, Collectors.toSet())
                    )
                )
            )

        // Even though [ResolveVariantDependenciesTask] does classpath reduction per module, the
        // final classpath here will not be accurate. For example, a dependency may appear twice in
        // both `release` and `default`. In order to correct this, we remove duplicates in non default
        // classPaths assuming the default classpath is the base classpath.
        val defaultClasspath = classPaths.getValue(DEFAULT_VARIANT)

        // Reduce non default classpath entries to contain only artifacts unique to them
        val reducedClasspath = classPaths
            .entries
            .parallelStream()
            .filter { it.key != DEFAULT_VARIANT }
            .filter { it.value.isNotEmpty() }
            .collect(
                Collectors.toConcurrentMap({ it.key }, { (_, dependencies) ->
                    dependencies.entries
                        .parallelStream()
                        .filter { it.key !in defaultClasspath }
                        .collect(Collectors.toMap({ it.key }, { it.value }))
                })
            ).apply { put(DEFAULT_VARIANT, defaultClasspath) }

        val flattenClasspath = reducedClasspath
            .entries
            .parallelStream()
            .collect(
                Collectors.toConcurrentMap(
                    { it.key },
                    { (_, dependencyMap) ->
                        dependencyMap
                            .entries
                            .parallelStream()
                            .collect(
                                flatMapping(
                                    { it.value.first().allDependencies.stream() },
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
                    })
            )

        val defaultFlatClasspath = flattenClasspath.getValue(DEFAULT_VARIANT)

        val reducedFinalClasspath = flattenClasspath
            .entries
            .parallelStream()
            .filter { it.key != DEFAULT_VARIANT }
            .filter { it.value.isNotEmpty() }
            .collect(
                Collectors.toConcurrentMap(
                    { (shortId, _) -> shortId },
                    { (_, dependencies) ->
                        dependencies.entries
                            .parallelStream()
                            .collect(
                                Collectors.toMap(
                                    { (shortId, _) -> shortId },
                                    { (shortId, dependency) ->
                                        // If this dependency is already in default classpath,
                                        // then we override it
                                        if (shortId in defaultFlatClasspath) {
                                            val (group, name, _, _) = dependency!!.id.split(":")
                                            dependency.copy(
                                                overrideTarget = OverrideTarget(
                                                    shortId,
                                                    BazelDependency.MavenDependency(
                                                        group = group,
                                                        name = name
                                                    )
                                                )
                                            )
                                        } else dependency
                                    })
                            )
                    })
            ).apply { put(DEFAULT_VARIANT, defaultFlatClasspath) }
            .mapValues { it.value.values }

        mergedDependencies.asFile.get().writeText(Json.encodeToString(reducedFinalClasspath))
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