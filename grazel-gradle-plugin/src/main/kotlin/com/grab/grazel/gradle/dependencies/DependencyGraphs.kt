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

import com.google.common.graph.Graphs
import com.google.common.graph.ImmutableValueGraph
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration


internal interface DependencyGraphs {
    val buildGraphs: Map<BuildGraphType, ImmutableValueGraph<Project, Configuration>>

    fun nodes(vararg buildGraphType: BuildGraphType): Set<Project>

    fun dependenciesSubGraph(
        project: Project,
        vararg buildGraphTypes: BuildGraphType
    ): Set<Project>

    fun directDependencies(
        project: Project,
        buildGraphType: BuildGraphType
    ): Set<Project>
}

internal class DefaultDependencyGraphs(
    override val buildGraphs: Map<BuildGraphType, ImmutableValueGraph<Project, Configuration>>
) : DependencyGraphs {
    override fun nodes(vararg buildGraphType: BuildGraphType): Set<Project> {
        return when {
            buildGraphType.isEmpty() -> buildGraphs.values.flatMap { it.nodes() }.toSet()
            else -> {
                buildGraphType.flatMap {
                    buildGraphs.getValue(it).nodes()
                }.toSet()
            }
        }
    }

    override fun dependenciesSubGraph(
        project: Project,
        vararg buildGraphTypes: BuildGraphType
    ): Set<Project> = if (buildGraphTypes.isEmpty()) {
        buildGraphs.values.flatMap {
            if (it.nodes().contains(project)) {
                Graphs.reachableNodes(it.asGraph(), project)
            } else {
                emptyList()
            }
        }
    } else {
        buildGraphTypes.flatMap { buildGraphType ->
            Graphs.reachableNodes(buildGraphs.getValue(buildGraphType).asGraph(), project)
        }
    }.toSet()

    override fun directDependencies(
        project: Project,
        buildGraphType: BuildGraphType
    ): Set<Project> = buildGraphs.getValue(buildGraphType).successors(project).toSet()
}