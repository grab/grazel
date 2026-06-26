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
        val graph = graphs.mergeToProjectGraph(variantTypeFilter).normalized()
        if (graph.isEmpty()) return emptyList()

        val components = stronglyConnectedComponents(graph)
        val componentIndexByProject = components
            .flatMapIndexed { index, projects -> projects.map { project -> project to index } }
            .toMap()
        val componentDependencies = components.indices.associateWithTo(mutableMapOf()) { mutableSetOf<Int>() }
        graph.forEach { (project, dependencies) ->
            val from = componentIndexByProject.getValue(project)
            dependencies.forEach { dependency ->
                val to = componentIndexByProject.getValue(dependency)
                if (from != to) {
                    componentDependencies.getValue(from).add(to)
                }
            }
        }

        return sortComponentIndexes(
            componentDependencies,
            compareBy { componentIndex -> components[componentIndex].first().path }
        )
            .asReversed()
            .map { componentIndex ->
                val projects = components[componentIndex]
                ProjectReachabilityGroup(
                    projects = projects,
                    cyclic = projects.size > 1 || projects.any { project -> project in graph.getValue(project) }
                )
            }
    }

    private fun Map<Project, Set<Project>>.normalized(): Map<Project, Set<Project>> {
        val projects = (keys + values.flatten()).toSortedSet(compareBy(Project::getPath))
        return projects.associateWith { project ->
            get(project).orEmpty().filterTo(sortedSetOf(compareBy(Project::getPath))) { dependency ->
                dependency in projects
            }
        }
    }

    private fun stronglyConnectedComponents(
        graph: Map<Project, Set<Project>>
    ): List<List<Project>> {
        val finishOrder = finishOrder(graph)
        val reversedGraph = reverseGraph(graph)
        val assigned = mutableSetOf<Project>()
        val components = mutableListOf<List<Project>>()

        finishOrder.asReversed().forEach { start ->
            if (start in assigned) return@forEach

            val component = mutableListOf<Project>()
            val stack = ArrayDeque<Project>()
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

            components += component.sortedBy(Project::getPath)
        }

        return components
    }

    private fun finishOrder(graph: Map<Project, Set<Project>>): List<Project> {
        val visited = mutableSetOf<Project>()
        val ordered = mutableListOf<Project>()

        graph.keys.sortedBy(Project::getPath).forEach { start ->
            if (!visited.add(start)) return@forEach

            val stack = ArrayDeque<Pair<Project, Iterator<Project>>>()
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

    private fun reverseGraph(graph: Map<Project, Set<Project>>): Map<Project, Set<Project>> {
        val reversed = graph.keys.associateWithTo(mutableMapOf<Project, MutableSet<Project>>()) {
            sortedSetOf(compareBy(Project::getPath))
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
}
