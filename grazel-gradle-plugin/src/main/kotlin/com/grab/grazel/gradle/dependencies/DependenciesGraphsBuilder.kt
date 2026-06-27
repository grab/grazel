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

import com.android.build.gradle.TestExtension
import com.google.common.graph.ImmutableValueGraph
import com.google.common.graph.MutableValueGraph
import com.google.common.graph.ValueGraphBuilder
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.ConfigurationDataSource
import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.isAndroidTest
import com.grab.grazel.gradle.isJava
import com.grab.grazel.gradle.isKotlinJvm
import com.grab.grazel.gradle.variant.AndroidVariantDataSource
import com.grab.grazel.gradle.variant.VariantGraphKey
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.jvmVariantName
import com.grab.grazel.gradle.variant.toJvmVariantType
import com.grab.grazel.gradle.variant.toVariantType
import org.gradle.api.Project
import org.gradle.kotlin.dsl.findByType
import javax.inject.Inject

internal class DependenciesGraphsBuilder
@Inject
constructor(
    @param:RootProject private val rootProject: Project,
    private val dependenciesDataSource: DependenciesDataSource,
    private val configurationDataSource: ConfigurationDataSource,
    private val androidVariantDataSource: AndroidVariantDataSource,
) {

    fun build(): DependencyGraphs {
        val variantGraphs: MutableMap<VariantGraphKey, MutableValueGraph<Project, DependencyGraphEdge>> =
            mutableMapOf()
        listOf(
            VariantType.AndroidBuild,
            VariantType.Test,
            VariantType.AndroidTest
        ).forEach { variantType ->
            rootProject.subprojects.forEach { sourceProject ->
                addProjectAsNodeToAllOfItsVariantsGraphs(
                    sourceProject,
                    variantType,
                    variantGraphs
                )
                addEdges(sourceProject, variantType, variantGraphs)
                dependenciesDataSource.projectDependencies(sourceProject, variantType)
                    .forEach { (configuration, projectDependency) ->
                        androidVariantDataSource.getMigratableVariants(
                            sourceProject,
                            variantType
                        ).forEach { variant ->
                            if (configurationDataSource.isThisConfigurationBelongsToThisVariants(
                                    sourceProject,
                                    variant,
                                    configuration = configuration
                                )
                            ) {
                                val variantKey = VariantGraphKey.from(
                                    sourceProject,
                                    variant.name,
                                    variant.toVariantType()
                                )
                                variantGraphs.putEdgeValue(
                                    variantKey,
                                    sourceProject,
                                    projectDependency.dependencyProject,
                                    ConfigurationEdge(configuration)
                                )
                            }
                        }
                    }
            }
        }
        addAndroidTestTargetProjectEdges(variantGraphs)

        val immutableVariantGraphs = variantGraphs
            .withDefault { buildGraph(0) }
            .mapValues { (_, graph) ->
                ImmutableValueGraph.copyOf(graph)
            }

        return DefaultDependencyGraphs(
            variantGraphs = immutableVariantGraphs
        )
    }

    private fun addEdges(
        project: Project,
        variantType: VariantType,
        variantGraph: MutableMap<VariantGraphKey, MutableValueGraph<Project, DependencyGraphEdge>>,
    ) {
        dependenciesDataSource.projectDependencies(project, variantType)
            .forEach { (configuration, projectDependency) ->
                val variants = androidVariantDataSource.getMigratableVariants(
                    project,
                    variantType
                )
                if (variants.isNotEmpty()) {
                    variants.forEach { variant ->
                        if (variant.compileConfiguration.hierarchy.contains(configuration)) {
                            val variantKey = VariantGraphKey.from(
                                project,
                                variant.name,
                                variant.toVariantType()
                            )
                            variantGraph.putEdgeValue(
                                variantKey,
                                project,
                                projectDependency.dependencyProject,
                                ConfigurationEdge(configuration)
                            )
                        }
                    }
                } else {
                    // For non-Android projects, create JVM variant keys
                    val variantKey = VariantGraphKey.from(
                        project,
                        variantType.jvmVariantName,
                        variantType.toJvmVariantType
                    )
                    variantGraph.putEdgeValue(
                        variantKey,
                        project,
                        projectDependency.dependencyProject,
                        ConfigurationEdge(configuration)
                    )
                }
            }
    }

    private fun addProjectAsNodeToAllOfItsVariantsGraphs(
        sourceProject: Project,
        variantType: VariantType,
        variantGraphs: MutableMap<VariantGraphKey, MutableValueGraph<Project, DependencyGraphEdge>>
    ) {
        if (sourceProject.isAndroid) {
            androidVariantDataSource.getMigratableVariants(sourceProject, variantType)
                .forEach { variant ->
                    val variantKey = VariantGraphKey.from(
                        sourceProject,
                        variant.name,
                        variant.toVariantType()
                    )
                    variantGraphs.addNode(variantKey, sourceProject)
                }
        } else if (
            !sourceProject.isAndroid &&
            (sourceProject.isJava || sourceProject.isKotlinJvm)
        ) {
            // For JVM projects
            val variantKey = VariantGraphKey.from(
                sourceProject,
                variantType.jvmVariantName,
                variantType.toJvmVariantType
            )
            variantGraphs.addNode(variantKey, sourceProject)
        } else {
            rootProject.logger.info("${sourceProject.name} is a simple directory")
        }
    }

    private fun addAndroidTestTargetProjectEdges(
        variantGraphs: MutableMap<VariantGraphKey, MutableValueGraph<Project, DependencyGraphEdge>>
    ) {
        rootProject.subprojects
            .filter(Project::isAndroidTest)
            .forEach { testProject ->
                val targetProjectPath = testProject.extensions
                    .findByType<TestExtension>()
                    ?.targetProjectPath
                    ?: return@forEach
                val targetProject = rootProject.findProject(targetProjectPath)
                    ?: return@forEach

                androidVariantDataSource.getMigratableVariants(testProject, VariantType.AndroidBuild)
                    .forEach { variant ->
                        val variantKey = VariantGraphKey.from(
                            testProject,
                            variant.name,
                            variant.toVariantType()
                        )
                        variantGraphs.putEdgeValue(
                            variantKey,
                            testProject,
                            targetProject,
                            AndroidTestTargetProjectEdge(targetProjectPath)
                        )
                    }
            }
    }
}

private fun MutableMap<VariantGraphKey, MutableValueGraph<Project, DependencyGraphEdge>>.putEdgeValue(
    variantKey: VariantGraphKey,
    sourceProject: Project,
    dependencyProject: Project,
    edge: DependencyGraphEdge
) {
    computeIfAbsent(variantKey) {
        buildGraph(sourceProject.subprojects.size)
    }
    get(variantKey)!!.putEdgeValue(sourceProject, dependencyProject, edge)
}

private fun MutableMap<VariantGraphKey, MutableValueGraph<Project, DependencyGraphEdge>>.addNode(
    variantKey: VariantGraphKey,
    sourceProject: Project
) {
    computeIfAbsent(variantKey) {
        buildGraph(sourceProject.subprojects.size)
    }
    get(variantKey)!!.addNode(sourceProject)
}

internal fun buildGraph(size: Int): MutableValueGraph<Project, DependencyGraphEdge> {
    return ValueGraphBuilder
        .directed()
        .allowsSelfLoops(false)
        .expectedNodeCount(size)
        .build()
}
