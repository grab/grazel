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
    fun sort(graphs: DependencyGraphs): List<Project> {
        val merged = graphs.mergeToProjectGraph()  // project → its dependencies

        if (merged.isEmpty()) {
            return emptyList()
        }

        // Build reverse map: project → projects that depend on it (dependents)
        val dependents = mutableMapOf<Project, MutableSet<Project>>()
        merged.keys.forEach { dependents[it] = mutableSetOf() }
        merged.forEach { (project, dependencies) ->
            dependencies.forEach { dep ->
                dependents.getOrPut(dep) { mutableSetOf() }.add(project)
            }
        }

        // In-degree = number of dependencies each project has
        val inDegrees = merged.mapValues { (_, deps) -> deps.size }.toMutableMap()

        // Queue starts with projects having zero dependencies (leaf nodes)
        // Sort by path to ensure deterministic ordering
        val queue = ArrayDeque(inDegrees.filterValues { it == 0 }.keys.sortedBy { it.path })
        val ordered = mutableListOf<Project>()

        // Process queue using Kahn's algorithm
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            ordered.add(current)

            // Collect newly ready projects and sort them deterministically
            val newlyReady = mutableListOf<Project>()
            dependents[current]?.forEach { dependent ->
                val newDegree = inDegrees[dependent]!! - 1
                inDegrees[dependent] = newDegree

                // If in-degree reaches zero, all dependencies are processed
                if (newDegree == 0) {
                    newlyReady.add(dependent)
                }
            }

            // Add in sorted order to ensure deterministic results
            newlyReady.sortedBy { it.path }.forEach { queue.add(it) }
        }

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
