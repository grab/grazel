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

package com.grab.grazel.fake

import com.google.common.graph.ImmutableValueGraph
import com.grab.grazel.gradle.dependencies.DependencyGraphEdge
import com.grab.grazel.gradle.dependencies.DependencyGraphDiagnosticEdge
import com.grab.grazel.gradle.dependencies.DependencyGraphNode
import com.grab.grazel.gradle.dependencies.DependencyGraphSourceSet
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.variant.VariantGraphKey
import com.grab.grazel.gradle.variant.VariantType
import org.gradle.api.Project

internal class FakeDependencyGraphs(
    private val directDeps: Set<Project> = emptySet(),
    private val dependenciesSubGraph: Set<Project> = emptySet(),
    private val nodes: Set<Project> = emptySet(),
    override val variantGraphs: Map<VariantGraphKey, ImmutableValueGraph<Project, DependencyGraphEdge>> = emptyMap(),
    private val projectGraph: Map<Project, Set<Project>> = emptyMap(),
    private val typedReachabilityGraph: Map<DependencyGraphNode, Set<DependencyGraphNode>>? = null
) : DependencyGraphs {

    override fun nodesByVariant(vararg variantKey: VariantGraphKey): Set<Project> = nodes

    override fun dependenciesSubGraphByVariant(
        project: Project,
        vararg variantKeys: VariantGraphKey
    ): Set<Project> = dependenciesSubGraph

    override fun directDependenciesByVariant(
        project: Project,
        variantKey: VariantGraphKey
    ): Set<Project> = directDeps

    override fun mergeToProjectGraph(
        variantTypeFilter: (VariantType) -> Boolean
    ): Map<Project, Set<Project>> = projectGraph

    override fun reachabilityGraph(
        variantTypeFilter: (VariantType) -> Boolean
    ): Map<DependencyGraphNode, Set<DependencyGraphNode>> = typedReachabilityGraph
        ?: projectGraph.mapKeys { (project, _) ->
            DependencyGraphNode(project, DependencyGraphSourceSet.Main)
        }.mapValues { (_, dependencies) ->
            dependencies.mapTo(sortedSetOf(compareBy<DependencyGraphNode> { it.project.path }
                .thenBy { it.sourceSet.ordinal })) { dependency ->
                DependencyGraphNode(dependency, DependencyGraphSourceSet.Main)
            }
        }

    override fun reachabilityDiagnosticEdges(
        variantTypeFilter: (VariantType) -> Boolean
    ): List<DependencyGraphDiagnosticEdge> = reachabilityGraph(variantTypeFilter).flatMap { (source, targets) ->
        targets.map { target ->
            DependencyGraphDiagnosticEdge(
                source = source,
                target = target,
                label = "FakeDependencyGraphEdge"
            )
        }
    }
}
