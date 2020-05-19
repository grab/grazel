/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.gradle

import com.google.common.graph.ImmutableValueGraph
import com.google.common.graph.MutableValueGraph
import com.google.common.graph.ValueGraphBuilder
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import javax.inject.Inject

internal class ProjectDependencyGraphBuilder @Inject constructor(
    @param:RootProject private val rootProject: Project,
    private val dependenciesDataSource: DependenciesDataSource
) {
    fun build(): ImmutableValueGraph<Project, Configuration> {

        data class EdgeData(
            val source: Project,
            val dependency: Project,
            val configuration: Configuration
        )

        val projectDependencyGraph: MutableValueGraph<Project, Configuration> =
            ValueGraphBuilder
                .directed()
                .allowsSelfLoops(false)
                .expectedNodeCount(rootProject.subprojects.size)
                .build()

        rootProject.subprojects
            .asSequence()
            .onEach { projectDependencyGraph.addNode(it) }
            .flatMap { sourceProject ->
                dependenciesDataSource.projectDependencies(sourceProject)
                    .map { (configuration, projectDependency) ->
                        EdgeData(
                            sourceProject,
                            projectDependency.dependencyProject,
                            configuration
                        )
                    }
            }.forEach { (source, dependency, configuration) ->
                projectDependencyGraph.putEdgeValue(
                    source,
                    dependency,
                    configuration
                )
            }
        return ImmutableValueGraph.copyOf(projectDependencyGraph)
    }
}