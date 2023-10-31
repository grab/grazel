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

package com.grab.grazel.gradle.dependencies

import com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitor.Companion.IGNORED_ARTIFACTS
import com.grab.grazel.util.ansiCyan
import com.grab.grazel.util.ansiGreen
import com.grab.grazel.util.ansiYellow
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.internal.artifacts.result.DefaultResolvedComponentResult
import java.util.*

private typealias Node = ResolvedComponentResult

/**
 * Visitor to flatten all components (including transitives) from a root [ResolvedComponentResult].
 * Ignore few artifacts specified by [IGNORED_ARTIFACTS]
 */
internal class ResolvedComponentsVisitor {

    private fun printIndented(level: Int, message: String, logger: (message: String) -> Unit) {
        val prefix = if (level == 0) "─" else " └"
        val indent = (0..level * 2).joinToString(separator = "") { "─" }
        val msg = message.let {
            when (level) {
                0 -> it.ansiCyan
                1 -> it.ansiGreen
                else -> it.ansiYellow
            }
        }
        logger("$prefix$indent $msg")
    }

    private val Node.isProject get() = toString().startsWith("project :")
    private val Node.repository
        get() = (this as? DefaultResolvedComponentResult)?.repositoryName ?: ""
    private val Node.shortId get() = toString().substringBeforeLast(":")

    private val ComponentSelector.isLegacySupportLibrary
        get() = toString().startsWith("com.android.support")

    data class VisitResult(
        val component: Node,
        val repository: String,
        val transitiveDeps: Set<DependencyResult>,
        val requiresJetifier: Boolean
    ) : Comparable<VisitResult> {
        override fun compareTo(
            other: VisitResult
        ) = component.toString().compareTo(other.component.toString())
    }

    /**
     * Holder to collect dependency information for a [Node]
     */
    data class DependencyResult(
        val dependency: Node,
        val requiresJetifier: Boolean,
        val unjetifiedSource: String?
    )

    /**
     * Visit all external dependency nodes in the graph and map them to [T] using the [transform]
     * function. Both current component and its transitive dependencies are provided in the callback
     *
     * @param root The root component usually [ResolutionResult.getRoot]
     * @param logger The logger to print traversal information in tree format
     * @param transform The callback used to convert to [T]
     */
    fun <T : Comparable<T>> visit(
        root: Node,
        logger: (message: String) -> Unit = { },
        transform: (visitResult: VisitResult) -> T?
    ): Set<T> {
        val allDependenciesMap = mutableMapOf<Node, MutableSet<DependencyResult>>()
        val visited = mutableSetOf<Node>()
        val result = TreeSet<T>(compareBy { it })

        /**
         * Do a depth-first visit to collect all transitive dependencies
         *
         * @param node Current component node
         * @param level The current traversal depth
         */
        fun dfs(node: Node, level: Int = 0) {
            if (node in visited) return
            visited.add(node)
            printIndented(level, node.toString(), logger)

            // Collection to collect all transitive dependencies
            val allDependencies = TreeSet<DependencyResult>(
                compareBy { it.dependency.toString() }
            )
            node.dependencies
                .asSequence()
                .filterIsInstance<ResolvedDependencyResult>()
                .map { it.selected to it.requested }
                .filter { (selected, _) -> !selected.isProject }
                .filter { (dep, _) -> IGNORED_ARTIFACTS.none { dep.toString().startsWith(it) } }
                .forEach { (directDependency, requested) ->
                    dfs(directDependency, level + 1)

                    allDependencies.add(
                        DependencyResult(
                            dependency = directDependency,
                            requiresJetifier = requested.isLegacySupportLibrary,
                            unjetifiedSource = JetifiedArtifacts[directDependency.shortId]
                        )
                    )
                    allDependencies.addAll(
                        allDependenciesMap[directDependency] ?: emptySet()
                    )
                }

            allDependenciesMap[node] = allDependencies

            if (!node.isProject) {
                transform(
                    VisitResult(
                        component = node,
                        repository = node.repository,
                        transitiveDeps = allDependencies,
                        requiresJetifier = allDependencies.any { it.requiresJetifier }
                    )
                )?.let(result::add)
            }
        }
        dfs(root)
        allDependenciesMap.clear()
        visited.clear()
        return result
    }

    companion object {
        private val IGNORED_ARTIFACTS = listOf(
            "org.jetbrains.kotlin:kotlin-parcelize-runtime"
        )
    }
}