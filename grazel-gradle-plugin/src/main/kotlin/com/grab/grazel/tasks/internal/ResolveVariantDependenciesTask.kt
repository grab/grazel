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

import com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitor
import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.gradle.variant.isBase
import com.grab.grazel.util.Json
import com.grab.grazel.util.dependsOn
import com.grab.grazel.util.fromJson
import dagger.Lazy
import kotlinx.serialization.encodeToString
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import java.util.TreeMap
import java.util.TreeSet
import kotlin.streams.asSequence

@CacheableTask
internal abstract class ResolveVariantDependenciesTask : DefaultTask() {

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val base: Property<Boolean>

    @get:Input
    abstract val compileConfiguration: ListProperty<ResolvedComponentResult>

    @get:Input
    abstract val compileDirectDependencies: MapProperty</*shortId*/ String, String>

    @get:Input
    abstract val compileExcludeRules: MapProperty</*shortId*/ String, Set<ExcludeRule>>

    @get:Input
    abstract val annotationProcessorConfiguration: ListProperty<ResolvedComponentResult>

    @get:Input
    abstract val kotlinCompilerPluginConfiguration: ListProperty<ResolvedComponentResult>

    @get:OutputFile
    abstract val resolvedDependencies: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baseDependenciesJsons: ListProperty<RegularFile>

    init {
        group = GRAZEL_TASK_GROUP
        description = "Resolves configurations and serializes them to be read on later"
    }

    private fun ListProperty<ResolvedComponentResult>.toResolvedDependencies(
        directDependenciesMap: Map<String, String> = emptyMap(),
        baseDependenciesMap: Map<String, String> = emptyMap(),
        excludeRulesMap: Map<String, Set<ExcludeRule>> = emptyMap(),
        removeTransitives: Boolean = false
    ): Set<ResolvedDependency> = get()
        .asSequence()
        .flatMap { root ->
            ResolvedComponentsVisitor().visit(
                root,
                logger::info
            ) { (component, repository, dependencies, jetifier) ->
                val version = component.moduleVersion!!
                val shortId = "${version.group}:${version.name}"
                val isDirect = shortId in directDependenciesMap
                val isUnique = shortId !in baseDependenciesMap
                val excludeRules = excludeRulesMap.getOrDefault(shortId, emptySet())
                if (isUnique) {
                    ResolvedDependency(
                        id = component.toString(),
                        shortId = shortId,
                        direct = isDirect,
                        version = version.version,
                        dependencies = dependencies.mapTo(TreeSet()) { (dependency, requiresJetifier, jetifierSource) ->
                            ResolvedDependency.createDependencyNotation(
                                dependency,
                                requiresJetifier,
                                jetifierSource
                            )
                        },
                        repository = repository,
                        excludeRules = excludeRules,
                        requiresJetifier = jetifier
                    )
                } else null
            }.asSequence()
        }.filter { if (removeTransitives) it.direct else true }.toSet()

    @TaskAction
    fun action() {
        val baseDependenciesMap = buildMap<String, String> {
            if (!base.get()) {
                // For non baseVariant tasks, every dependency that appears in the base task's json output
                // is considered direct dependencies, hence parse it add to [directDependenciesMap]
                baseDependenciesJsons.get()
                    .stream()
                    .parallel()
                    .map<ResolveDependenciesResult>(::fromJson)
                    .sequential()
                    .asSequence()
                    .flatMap { it.dependencies.getValue(COMPILE.name) }
                    .groupBy(ResolvedDependency::shortId, ResolvedDependency::direct)
                    .mapValues { entry -> entry.value.any { it } }
                    .forEach { (shortId, direct) ->
                        if (direct) put(shortId, shortId)
                    }
            }
        }

        val resolvedDependenciesResult = ResolveDependenciesResult(
            variantName = variantName.get(),
            dependencies = buildMap {
                put(
                    COMPILE.name,
                    compileConfiguration.toResolvedDependencies(
                        directDependenciesMap = compileDirectDependencies.get(),
                        baseDependenciesMap = baseDependenciesMap,
                        excludeRulesMap = compileExcludeRules.get(),
                        removeTransitives = /*!base.get()*/ true,
                    )
                )
            }
        )
        resolvedDependencies.get()
            .asFile
            .writeText(Json.encodeToString(resolvedDependenciesResult))
    }

    companion object {
        internal fun register(
            rootProject: Project,
            variantBuilderProvider: Lazy<VariantBuilder>,
            subprojectTaskConfigure: (TaskProvider<ResolveVariantDependenciesTask>) -> Unit
        ) {
            // Register a lifecycle to aggregate all subproject tasks
            val rootResolveDependenciesTask = rootProject.tasks.register("resolveDependencies") {
                group = GRAZEL_TASK_GROUP
                description = "Resolve variant tasks dependencies"
            }
            rootProject.afterEvaluate {
                val variantBuilder = variantBuilderProvider.get()
                subprojects.forEach { project ->
                    val projectResolveDependenciesTask = project.tasks
                        .register("resolveDependencies") {
                            group = GRAZEL_TASK_GROUP
                            description = "Resolve variant tasks dependencies"
                        }
                    // First pass to create all tasks
                    variantBuilder.onVariants(project) { variant ->
                        processVariant(
                            project,
                            variant,
                            rootResolveDependenciesTask,
                            projectResolveDependenciesTask
                        )
                    }
                    // Second pass to establish inter-dependencies based on extendsFrom property
                    variantBuilder.onVariants(project) { variant ->
                        configureVariantTaskDependencies(project, variant, subprojectTaskConfigure)
                    }
                }
            }
        }

        private fun ExternalDependency.extractExcludeRules(): Set<ExcludeRule> {
            return excludeRules
                .map {
                    @Suppress("USELESS_ELVIS") // Gradle lying, module can be null
                    (ExcludeRule(
                        it.group,
                        it.module ?: ""
                    ))
                }
                .filterNot { it.artifact.isNullOrBlank() }
                // TODO(arun) Respect excludeArtifactsDenyList
                //.filterNot { it.toString() in excludeArtifactsDenyList }
                .toSet()
        }

        private fun processVariant(
            project: Project,
            variant: Variant<*>,
            rootResolveDependenciesTask: TaskProvider<Task>,
            projectResolveDependenciesTask: TaskProvider<Task>,
        ) {
            val compileConfigurationComponents = project.provider {
                variant.compileConfiguration
                    .map { it.incoming.resolutionResult.root }
                    .toList()
            }

            val directDependenciesCompile = project.provider {
                variant.compileConfiguration
                    .asSequence()
                    .flatMap { it.incoming.dependencies }
                    .filterIsInstance<ExternalDependency>()
                    .associateTo(TreeMap()) { "${it.group}:${it.name}" to "${it.group}:${it.name}" }
            }

            val excludeRulesCompile = project.provider {
                variant.compileConfiguration
                    .asSequence()
                    .flatMap { it.incoming.dependencies }
                    .filterIsInstance<ExternalDependency>()
                    .groupByTo(TreeMap()) { dep -> "${dep.group}:${dep.name}" }
                    .mapValues { (_, artifacts) ->
                        artifacts.flatMap { it.extractExcludeRules() }.toSet()
                    }.filterValues { it.isNotEmpty() }

            }

            val resolvedDependenciesJson = project.layout
                .buildDirectory
                .file("grazel/${variant.name}/dependencies.json")

            val resolveVariantDependenciesTask = project.tasks
                .register<ResolveVariantDependenciesTask>(
                    variant.name + "ResolveDependencies"
                ) {
                    variantName.set(variant.name)
                    base.set(variant.isBase)
                    compileConfiguration.set(compileConfigurationComponents)
                    compileDirectDependencies.set(directDependenciesCompile)
                    compileExcludeRules.set(excludeRulesCompile)
                    resolvedDependencies.set(resolvedDependenciesJson)
                }
            rootResolveDependenciesTask.dependsOn(resolveVariantDependenciesTask)
            projectResolveDependenciesTask.dependsOn(resolveVariantDependenciesTask)
        }


        private fun configureVariantTaskDependencies(
            project: Project,
            variant: Variant<*>,
            subprojectTaskConfigure: (TaskProvider<ResolveVariantDependenciesTask>) -> Unit
        ) {
            val tasks = project.tasks
            val taskName = variant.name + "ResolveDependencies"
            val resolveTask = tasks.named<ResolveVariantDependenciesTask>(taskName)
            resolveTask.configure {
                val variantTask = this
                variant.extendsFrom.forEach { extends ->
                    try {
                        val extendsTasksName = extends + "ResolveDependencies"
                        val extendsTask = tasks.named<ResolveVariantDependenciesTask>(
                            extendsTasksName
                        )
                        variantTask.baseDependenciesJsons.add(extendsTask.flatMap { it.resolvedDependencies })
                    } catch (e: Exception) {
                        // TODO(arun) Handle gracefully
                    }
                }
            }
            subprojectTaskConfigure(resolveTask)
        }
    }
}