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

package com.grab.grazel.gradle.dependencies

import com.google.common.graph.ImmutableValueGraph
import com.google.common.graph.MutableValueGraph
import com.google.common.graph.ValueGraphBuilder
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.extension.TestExtension
import com.grab.grazel.gradle.AndroidVariantDataSource
import com.grab.grazel.gradle.ConfigurationDataSource
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.isJava
import com.grab.grazel.gradle.isKotlinJvm
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import javax.inject.Inject

internal class DependenciesGraphsBuilder @Inject constructor(
    @param:RootProject private val rootProject: Project,
    private val dependenciesDataSource: DependenciesDataSource,
    private val configurationDataSource: ConfigurationDataSource,
    private val androidVariantDataSource: AndroidVariantDataSource,
    private val testExtension: TestExtension,
) {

    fun build(): DependencyGraphs {
        val buildGraphs: MutableMap<BuildGraphType, MutableValueGraph<Project, Configuration>> =
            mutableMapOf()
        buildList {
            add(ConfigurationScope.BUILD)
            if (testExtension.enableTestMigration) {
                add(ConfigurationScope.TEST)
                add(ConfigurationScope.ANDROID_TEST)
            }
        }.forEach { configurationScope ->
            rootProject.subprojects.forEach { sourceProject ->
                addProjectAsNodeToAllOfItsVariantsGraphs(
                    sourceProject,
                    configurationScope,
                    buildGraphs
                )
                addEdges(sourceProject, configurationScope, buildGraphs)
                dependenciesDataSource.projectDependencies(sourceProject, configurationScope)
                    .forEach { (configuration, projectDependency) ->
                        androidVariantDataSource.getMigratableVariants(
                            sourceProject,
                            configurationScope
                        ).forEach { variant ->
                            if (configurationDataSource.isThisConfigurationBelongsToThisVariants(
                                    sourceProject,
                                    variant,
                                    configuration = configuration
                                )
                            ) {
                                buildGraphs.putEdgeValue(
                                    BuildGraphType(configurationScope, variant),
                                    sourceProject,
                                    projectDependency.dependencyProject,
                                    configuration
                                )
                            }
                        }
                    }
            }
        }

        val immutableBuildGraphs = buildGraphs
            .withDefault { buildGraph(0) }
            .mapValues { (_, graph) ->
                ImmutableValueGraph.copyOf(graph)
            }

        return DefaultDependencyGraphs(buildGraphs = immutableBuildGraphs)
    }

    private fun addEdges(
        project: Project,
        configurationScope: ConfigurationScope,
        graph: MutableMap<BuildGraphType, MutableValueGraph<Project, Configuration>>,
    ) {
        dependenciesDataSource.projectDependencies(project, configurationScope)
            .forEach { (configuration, projectDependency) ->
                val variants = androidVariantDataSource.getMigratableVariants(
                    project,
                    configurationScope
                )
                if (variants.isNotEmpty()) {
                    variants.forEach { variant ->
                        if (variant.compileConfiguration.hierarchy.contains(configuration)) {
                            graph.putEdgeValue(
                                BuildGraphType(configurationScope, variant),
                                project,
                                projectDependency.dependencyProject,
                                configuration
                            )
                        }
                    }
                } else {
                    graph.putEdgeValue(
                        BuildGraphType(configurationScope),
                        project,
                        projectDependency.dependencyProject,
                        configuration
                    )
                }
            }
    }

    private fun addProjectAsNodeToAllOfItsVariantsGraphs(
        sourceProject: Project,
        configurationScope: ConfigurationScope,
        buildGraphs: MutableMap<BuildGraphType, MutableValueGraph<Project, Configuration>>
    ) {
        if (sourceProject.isAndroid) {
            androidVariantDataSource.getMigratableVariants(sourceProject, configurationScope)
                .forEach { variant ->
                    buildGraphs.addNode(BuildGraphType(configurationScope, variant), sourceProject)
                }
        } else if (
            !sourceProject.isAndroid &&
            (sourceProject.isJava || sourceProject.isKotlinJvm)
        ) {
            buildGraphs.addNode(BuildGraphType(configurationScope, null), sourceProject)
        } else {
            rootProject.logger.info("${sourceProject.name} is a simple directory")
        }
    }
}

private fun MutableMap<BuildGraphType, MutableValueGraph<Project, Configuration>>.putEdgeValue(
    buildGraphType: BuildGraphType,
    sourceProject: Project,
    dependencyProject: Project,
    configuration: Configuration
) {
    computeIfAbsent(buildGraphType) {
        buildGraph(sourceProject.subprojects.size)
    }
    get(buildGraphType)!!.putEdgeValue(sourceProject, dependencyProject, configuration)
}

private fun MutableMap<BuildGraphType, MutableValueGraph<Project, Configuration>>.addNode(
    buildGraphType: BuildGraphType,
    sourceProject: Project
) {
    computeIfAbsent(buildGraphType) {
        buildGraph(sourceProject.subprojects.size)
    }
    get(buildGraphType)!!.addNode(sourceProject)
}

fun buildGraph(size: Int): MutableValueGraph<Project, Configuration> {
    return ValueGraphBuilder
        .directed()
        .allowsSelfLoops(false)
        .expectedNodeCount(size)
        .build()
}
