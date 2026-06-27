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

import com.grab.grazel.gradle.variant.VariantType
import org.gradle.api.Project
import java.util.ArrayDeque

/**
 * Topological sorting utility using Kahn's algorithm.
 */
internal object TopologicalSorter {
    /**
     * Returns projects in topological order (dependencies before dependents).
     *
     * @param graphs The dependency graphs to merge and sort
     * @return List of projects ordered such that dependencies appear before dependents
     * @throws IllegalStateException if a cycle is detected in the dependency graph
     */
    fun sort(
        graphs: DependencyGraphs,
        variantTypeFilter: (VariantType) -> Boolean = { it.isBuildGraph }
    ): List<Project> {
        val merged = graphs.mergeToProjectGraph(variantTypeFilter)  // project → its dependencies

        if (merged.isEmpty()) {
            return emptyList()
        }

        val ordered = dependencyFirstOrder(merged, compareBy(Project::getPath))

        // Detect cycles: if we didn't process all projects, there must be a cycle
        check(ordered.size == merged.size) {
            val unprocessed = merged.keys - ordered.toSet()
            val cyclePath = findCycle(merged, unprocessed)
            val cycleMessage = if (cyclePath.isNotEmpty()) {
                "Cycle path: ${cyclePath.joinToString(" -> ") { it.path }}"
            } else {
                "Unable to determine exact cycle path"
            }

            buildString {
                appendLine("Cycle detected in dependency graph.")
                appendLine(cycleMessage)
                appendLine()
                append("Unprocessed projects (may include projects blocked by cycle): ")
                append(unprocessed.map { it.path }.sorted())
            }
        }

        return ordered
    }

    /**
     * Finds an actual cycle path using iterative DFS.
     */
    private fun findCycle(
        graph: Map<Project, Set<Project>>,
        unprocessed: Set<Project>
    ): List<Project> {
        val visited = mutableSetOf<Project>()

        for (startNode in unprocessed.sortedBy { it.path }) {
            if (startNode in visited) continue

            val recursionStack = mutableSetOf<Project>()
            val parent = mutableMapOf<Project, Project?>()
            val stack = ArrayDeque<Pair<Project, Iterator<Project>>>()

            val startDeps = graph[startNode]?.filter { it in unprocessed }?.sortedBy { it.path } ?: emptyList()
            stack.addLast(startNode to startDeps.iterator())
            recursionStack.add(startNode)
            parent[startNode] = null

            while (stack.isNotEmpty()) {
                val (current, iterator) = stack.last()

                if (iterator.hasNext()) {
                    val neighbor = iterator.next()

                    if (neighbor in recursionStack) {
                        return reconstructCyclePath(neighbor, current, parent)
                    }

                    if (neighbor !in visited && neighbor in unprocessed) {
                        recursionStack.add(neighbor)
                        parent[neighbor] = current
                        val neighborDeps = graph[neighbor]?.filter { it in unprocessed }?.sortedBy { it.path } ?: emptyList()
                        stack.addLast(neighbor to neighborDeps.iterator())
                    }
                } else {
                    stack.removeLast()
                    recursionStack.remove(current)
                    visited.add(current)
                }
            }
        }
        return emptyList()
    }

    private fun reconstructCyclePath(
        cycleStart: Project,
        cycleEnd: Project,
        parent: Map<Project, Project?>
    ): List<Project> {
        val path = mutableListOf<Project>()
        var current: Project? = cycleEnd

        while (current != null && current != cycleStart) {
            path.add(current)
            current = parent[current]
        }
        path.add(cycleStart)
        path.reverse()
        path.add(cycleStart)

        return path
    }
}

private fun <T> dependencyFirstOrder(
    dependencyGraph: Map<T, Set<T>>,
    comparator: Comparator<T>
): List<T> {
    val dependents = mutableMapOf<T, MutableSet<T>>()
    dependencyGraph.keys.forEach { dependents[it] = mutableSetOf() }
    dependencyGraph.forEach { (node, dependencies) ->
        dependencies.forEach { dependency ->
            dependents.getOrPut(dependency) { mutableSetOf() }.add(node)
        }
    }

    val inDegrees = dependencyGraph.mapValues { (_, dependencies) -> dependencies.size }.toMutableMap()
    val queue = ArrayDeque(inDegrees.filterValues { it == 0 }.keys.sortedWith(comparator))
    val ordered = mutableListOf<T>()

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        ordered += current
        val newlyReady = mutableListOf<T>()
        dependents[current].orEmpty().forEach { dependent ->
            val newDegree = inDegrees.getValue(dependent) - 1
            inDegrees[dependent] = newDegree
            if (newDegree == 0) {
                newlyReady += dependent
            }
        }
        newlyReady.sortedWith(comparator).forEach(queue::add)
    }

    return ordered
}

internal data class ProjectReachabilityGroup(
    val projects: List<Project>,
    val cyclic: Boolean
)

internal object ProjectReachabilityOrder {
    fun consumersFirstGroups(
        graphs: DependencyGraphs,
        variantTypeFilter: (VariantType) -> Boolean = { true }
    ): List<ProjectReachabilityGroup> {
        val graph = graphs.reachabilityGraph(variantTypeFilter).normalized(
            comparator = dependencyGraphNodeComparator
        )
        if (graph.isEmpty()) return emptyList()

        val components = stronglyConnectedComponents(graph, dependencyGraphNodeComparator)
        val cyclicComponents = components.filter { nodes ->
            nodes.size > 1 || nodes.any { node -> node in graph.getValue(node) }
        }
        check(cyclicComponents.isEmpty()) {
            typedCycleDiagnostic(
                cyclicComponents = cyclicComponents,
                diagnosticEdges = graphs.reachabilityDiagnosticEdges(variantTypeFilter)
            )
        }

        val componentIndexByNode = components
            .flatMapIndexed { index, nodes -> nodes.map { node -> node to index } }
            .toMap()
        val componentDependencies = components.indices.associateWithTo(mutableMapOf()) { mutableSetOf<Int>() }
        graph.forEach { (node, dependencies) ->
            val from = componentIndexByNode.getValue(node)
            dependencies.forEach { dependency ->
                val to = componentIndexByNode.getValue(dependency)
                if (from != to) {
                    componentDependencies.getValue(from).add(to)
                }
            }
        }

        val seenProjects = mutableSetOf<Project>()
        return sortComponentIndexes(
            componentDependencies,
            compareBy { componentIndex -> components[componentIndex].first().project.path }
        )
            .asReversed()
            .mapNotNull { componentIndex ->
                val nodes = components[componentIndex]
                val projects = nodes
                    .map(DependencyGraphNode::project)
                    .distinctBy(Project::getPath)
                    .filter { project ->
                        seenProjects.add(project)
                    }
                if (projects.isEmpty()) {
                    null
                } else {
                    ProjectReachabilityGroup(
                        projects = projects.sortedBy(Project::getPath),
                        cyclic = false
                    )
                }
            }
    }

    private fun typedCycleDiagnostic(
        cyclicComponents: List<List<DependencyGraphNode>>,
        diagnosticEdges: List<DependencyGraphDiagnosticEdge>
    ): String = buildString {
        appendLine("Typed dependency cycle detected in reachability graph.")
        appendLine("SCC is not a modeling strategy; model missing typed edges instead of running a fixpoint.")
        cyclicComponents.forEachIndexed { index, nodes ->
            val nodeSet = nodes.toSet()
            appendLine("Component ${index + 1}: ${nodes.joinToString(", ") { it.displayName() }}")
            val componentEdges = diagnosticEdges
                .filter { edge -> edge.source in nodeSet && edge.target in nodeSet }
                .sortedWith(
                    compareBy<DependencyGraphDiagnosticEdge> { it.source.displayName() }
                        .thenBy { it.target.displayName() }
                        .thenBy { it.label }
                )
            if (componentEdges.isEmpty()) {
                appendLine("  edges: unavailable")
            } else {
                componentEdges.forEach { edge ->
                    appendLine("  ${edge.source.displayName()} -> ${edge.target.displayName()} via ${edge.label}")
                }
            }
        }
    }

    private fun DependencyGraphNode.displayName(): String = "${project.path}[$sourceSet]"

    private fun <T> Map<T, Set<T>>.normalized(comparator: Comparator<T>): Map<T, Set<T>> {
        val nodes = (keys + values.flatten()).toSortedSet(comparator)
        return nodes.associateWith { node ->
            get(node).orEmpty().filterTo(sortedSetOf(comparator)) { dependency ->
                dependency in nodes
            }
        }
    }

    private fun <T> stronglyConnectedComponents(
        graph: Map<T, Set<T>>,
        comparator: Comparator<T>
    ): List<List<T>> {
        val finishOrder = finishOrder(graph, comparator)
        val reversedGraph = reverseGraph(graph, comparator)
        val assigned = mutableSetOf<T>()
        val components = mutableListOf<List<T>>()

        finishOrder.asReversed().forEach { start ->
            if (start in assigned) return@forEach

            val component = mutableListOf<T>()
            val stack = ArrayDeque<T>()
            stack.addLast(start)
            assigned += start

            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                component += current
                reversedGraph.getValue(current).forEach { dependent ->
                    if (assigned.add(dependent)) {
                        stack.addLast(dependent)
                    }
                }
            }

            components += component.sortedWith(comparator)
        }

        return components
    }

    private fun <T> finishOrder(graph: Map<T, Set<T>>, comparator: Comparator<T>): List<T> {
        val visited = mutableSetOf<T>()
        val ordered = mutableListOf<T>()

        graph.keys.sortedWith(comparator).forEach { start ->
            if (!visited.add(start)) return@forEach

            val stack = ArrayDeque<Pair<T, Iterator<T>>>()
            stack.addLast(start to graph.getValue(start).iterator())

            while (stack.isNotEmpty()) {
                val (current, dependencies) = stack.last()
                if (dependencies.hasNext()) {
                    val dependency = dependencies.next()
                    if (visited.add(dependency)) {
                        stack.addLast(dependency to graph.getValue(dependency).iterator())
                    }
                } else {
                    stack.removeLast()
                    ordered += current
                }
            }
        }

        return ordered
    }

    private fun <T> reverseGraph(graph: Map<T, Set<T>>, comparator: Comparator<T>): Map<T, Set<T>> {
        val reversed = graph.keys.associateWithTo(mutableMapOf<T, MutableSet<T>>()) {
            sortedSetOf(comparator)
        }
        graph.forEach { (project, dependencies) ->
            dependencies.forEach { dependency ->
                reversed.getValue(dependency).add(project)
            }
        }
        return reversed
    }

    private fun sortComponentIndexes(
        componentDependencies: Map<Int, Set<Int>>,
        comparator: Comparator<Int>
    ): List<Int> {
        val ordered = dependencyFirstOrder(componentDependencies, comparator)
        check(ordered.size == componentDependencies.size) {
            "Cycle detected in condensed dependency graph."
        }
        return ordered
    }

    private val dependencyGraphNodeComparator: Comparator<DependencyGraphNode> =
        compareBy<DependencyGraphNode> { it.project.path }
            .thenBy { it.sourceSet.ordinal }
}
