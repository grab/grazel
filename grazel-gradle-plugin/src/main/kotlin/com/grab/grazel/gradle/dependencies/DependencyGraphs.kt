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
import com.grab.grazel.gradle.isAndroidTest
import com.grab.grazel.gradle.variant.VariantGraphKey
import com.grab.grazel.gradle.variant.VariantType
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

internal sealed interface DependencyGraphEdge

internal data class ConfigurationEdge(
    val configuration: Configuration
) : DependencyGraphEdge

internal data class AndroidTestTargetProjectEdge(
    val targetProjectPath: String
) : DependencyGraphEdge

internal enum class DependencyGraphSourceSet {
    Main,
    Test,
    AndroidTest
}

internal data class DependencyGraphNode(
    val project: Project,
    val sourceSet: DependencyGraphSourceSet
)

/** Orders [DependencyGraphNode]s by project path, then by source-set ordinal. */
internal val dependencyGraphNodeComparator: Comparator<DependencyGraphNode> =
    compareBy<DependencyGraphNode> { it.project.path }
        .thenBy { it.sourceSet.ordinal }

internal data class DependencyGraphDiagnosticEdge(
    val source: DependencyGraphNode,
    val target: DependencyGraphNode,
    val label: String
)

internal interface DependencyGraphs {
    /**
     * Graph keyed by [VariantGraphKey]. Maps variant IDs to their corresponding dependency graphs.
     */
    val variantGraphs: Map<VariantGraphKey, ImmutableValueGraph<Project, DependencyGraphEdge>>

    /**
     * Returns all project nodes from graphs matching the given variant keys. If no keys are
     * provided, returns nodes from all variant graphs.
     */
    fun nodesByVariant(vararg variantKey: VariantGraphKey): Set<Project>

    /**
     * Returns the transitive dependency subgraph for a project, filtered by variant keys. If no
     * keys are provided, returns dependencies from all variant graphs.
     */
    fun dependenciesSubGraphByVariant(
        project: Project,
        vararg variantKeys: VariantGraphKey
    ): Set<Project>

    /** Returns direct dependencies for a project by variant key. */
    fun directDependenciesByVariant(
        project: Project,
        variantKey: VariantGraphKey
    ): Set<Project>

    /**
     * Merges variant graphs into a project-level dependency graph.
     *
     * @param variantTypeFilter Predicate to filter which variant types to include. Defaults to
     *    build graphs only (AndroidBuild, JvmBuild) to avoid artificial cycles from test
     *    dependencies.
     * @return Map of projects to their direct dependencies across filtered variants.
     */
    fun mergeToProjectGraph(
        variantTypeFilter: (VariantType) -> Boolean = { it.isBuildGraph }
    ): Map<Project, Set<Project>>

    /**
     * Returns a reachability graph that preserves source-set identity until ordering completes.
     * Edges still point to project production output unless a typed edge says otherwise.
     */
    fun reachabilityGraph(
        variantTypeFilter: (VariantType) -> Boolean = { true }
    ): Map<DependencyGraphNode, Set<DependencyGraphNode>>

    /**
     * Returns typed reachability edges with diagnostic labels. This is intentionally separate
     * from ordering so normal code does not infer policy from edge labels.
     */
    fun reachabilityDiagnosticEdges(
        variantTypeFilter: (VariantType) -> Boolean = { true }
    ): List<DependencyGraphDiagnosticEdge>
}

internal class DefaultDependencyGraphs(
    override val variantGraphs: Map<VariantGraphKey, ImmutableValueGraph<Project, DependencyGraphEdge>>
) : DependencyGraphs {

    override fun nodesByVariant(vararg variantKey: VariantGraphKey): Set<Project> {
        return when {
            variantKey.isEmpty() -> variantGraphs.values.flatMap { it.nodes() }.toSet()
            else -> {
                variantKey.flatMap {
                    variantGraphs[it]?.nodes() ?: emptySet()
                }.toSet()
            }
        }
    }

    override fun dependenciesSubGraphByVariant(
        project: Project,
        vararg variantKeys: VariantGraphKey
    ): Set<Project> = if (variantKeys.isEmpty()) {
        variantGraphs.values.flatMap {
            if (it.nodes().contains(project)) {
                Graphs.reachableNodes(it.asGraph(), project)
            } else {
                emptyList()
            }
        }
    } else {
        variantKeys.flatMap { variantKey ->
            variantGraphs[variantKey]?.let { graph ->
                if (graph.nodes().contains(project)) {
                    Graphs.reachableNodes(graph.asGraph(), project)
                } else {
                    emptySet()
                }
            } ?: emptySet()
        }
    }.toSet()

    override fun directDependenciesByVariant(
        project: Project,
        variantKey: VariantGraphKey
    ): Set<Project> = variantGraphs[variantKey]?.successors(project)?.toSet() ?: emptySet()

    override fun mergeToProjectGraph(
        variantTypeFilter: (VariantType) -> Boolean
    ): Map<Project, Set<Project>> {
        // Filter graphs by variant type
        val filteredGraphs = variantGraphs.filterKeys { key ->
            variantTypeFilter(key.variantType)
        }

        val allProjects = filteredGraphs.values.flatMap { it.nodes() }.toSet()
        return allProjects.associateWith { project ->
            filteredGraphs.values.flatMap { graph ->
                if (graph.nodes().contains(project)) {
                    graph.successors(project)
                } else emptySet()
            }.toSet()
        }
    }

    override fun reachabilityGraph(
        variantTypeFilter: (VariantType) -> Boolean
    ): Map<DependencyGraphNode, Set<DependencyGraphNode>> =
        reachabilityProjection(variantTypeFilter).graph

    override fun reachabilityDiagnosticEdges(
        variantTypeFilter: (VariantType) -> Boolean
    ): List<DependencyGraphDiagnosticEdge> =
        reachabilityProjection(variantTypeFilter).diagnosticEdges

    private fun reachabilityProjection(
        variantTypeFilter: (VariantType) -> Boolean
    ): ReachabilityProjection {
        val typedGraph = sortedMapOf<DependencyGraphNode, MutableSet<DependencyGraphNode>>(
            dependencyGraphNodeComparator
        )
        val diagnosticEdges = mutableListOf<DependencyGraphDiagnosticEdge>()
        variantGraphs
            .filterKeys { key -> variantTypeFilter(key.variantType) }
            .forEach { (variantKey, graph) ->
                graph.nodes().forEach { project ->
                    if (graph.successors(project).isNotEmpty() || graph.adjacentNodes(project).isEmpty()) {
                        val source = sourceNode(
                            project = project,
                            variantKey = variantKey
                        )
                        typedGraph.getOrPut(source) {
                            sortedSetOf(dependencyGraphNodeComparator)
                        }
                        source.mainNodeIfInherited()?.let { mainNode ->
                            typedGraph.getValue(source).add(mainNode)
                            diagnosticEdges += DependencyGraphDiagnosticEdge(
                                source = source,
                                target = mainNode,
                                label = "SourceSetInheritanceEdge(${source.sourceSet}->Main)"
                            )
                            typedGraph.getOrPut(mainNode) {
                                sortedSetOf(dependencyGraphNodeComparator)
                            }
                        }
                    }
                }
                graph.edges().forEach { edge ->
                    val source = sourceNode(
                        project = edge.nodeU(),
                        variantKey = variantKey
                    )
                    val target = DependencyGraphNode(edge.nodeV(), DependencyGraphSourceSet.Main)
                    val edgeValue = graph.edgeValue(edge.nodeU(), edge.nodeV()).orElse(null)
                    typedGraph.getOrPut(source) {
                        sortedSetOf(dependencyGraphNodeComparator)
                    }.add(target)
                    diagnosticEdges += DependencyGraphDiagnosticEdge(
                        source = source,
                        target = target,
                        label = edgeValue.diagnosticLabel()
                    )
                    typedGraph.getOrPut(target) {
                        sortedSetOf(dependencyGraphNodeComparator)
                    }
                }
            }
        return ReachabilityProjection(
            graph = typedGraph.mapValues { (_, dependencies) -> dependencies.toSet() },
            diagnosticEdges = diagnosticEdges
        )
    }

    private fun sourceNode(
        project: Project,
        variantKey: VariantGraphKey
    ): DependencyGraphNode =
        DependencyGraphNode(
            project = project,
            sourceSet = when {
                variantKey.variantType == VariantType.Test -> DependencyGraphSourceSet.Test
                variantKey.variantType == VariantType.AndroidTest -> DependencyGraphSourceSet.AndroidTest
                project.isAndroidTest -> DependencyGraphSourceSet.AndroidTest
                else -> DependencyGraphSourceSet.Main
            }
        )

    private fun DependencyGraphNode.mainNodeIfInherited(): DependencyGraphNode? =
        when (sourceSet) {
            DependencyGraphSourceSet.Main -> null
            DependencyGraphSourceSet.Test,
            DependencyGraphSourceSet.AndroidTest -> copy(sourceSet = DependencyGraphSourceSet.Main)
        }

    private fun DependencyGraphEdge?.diagnosticLabel(): String =
        when (this) {
            is AndroidTestTargetProjectEdge -> "AndroidTestTargetProjectEdge($targetProjectPath)"
            is ConfigurationEdge -> "ConfigurationEdge(${configuration.name})"
            null -> "UnknownDependencyGraphEdge"
        }

    private data class ReachabilityProjection(
        val graph: Map<DependencyGraphNode, Set<DependencyGraphNode>>,
        val diagnosticEdges: List<DependencyGraphDiagnosticEdge>
    )
}
