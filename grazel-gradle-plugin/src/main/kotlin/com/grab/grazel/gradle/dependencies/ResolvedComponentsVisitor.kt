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
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.internal.artifacts.result.DefaultResolvedComponentResult
import java.util.TreeSet

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

    /**
     * Visit all external dependency nodes in the graph and map them to [T] using the [transform]
     * function. Both current component and its transitive dependencies are provided in the callback
     *
     * @param root The root component usually [ResolutionResult.getRoot]
     * @param transform The callback used to convert to [T]
     */
    fun <T : Comparable<T>> visit(
        root: Node,
        logger: (message: String) -> Unit = { },
        transform: (component: Node, repository: String, dependencies: Set<String>) -> T?
    ): Set<T> {
        val transitiveClosureMap = mutableMapOf<Node, MutableSet<Node>>()
        val visited = mutableSetOf<Node>()
        val result = TreeSet<T>(compareBy { it })

        /**
         * Do a depth-first visit to collect all transitive dependencies
         */
        fun dfs(node: Node, level: Int = 0) {
            if (node in visited) return
            visited.add(node)
            printIndented(level, node.toString(), logger)

            val transitiveClosure = TreeSet(compareBy(Node::toString))
            node.dependencies
                .asSequence()
                .filterIsInstance<ResolvedDependencyResult>()
                .map { it.selected }
                .filter { !it.isProject }
                .filter { dep -> IGNORED_ARTIFACTS.none { dep.toString().startsWith(it) } }
                .forEach { directDependency ->
                    dfs(directDependency, level + 1)

                    transitiveClosure.add(directDependency)
                    transitiveClosure.addAll(transitiveClosureMap[directDependency] ?: emptySet())
                }
            transitiveClosureMap[node] = transitiveClosure
            // TODO(arun) Memoize the transform
            if (!node.isProject) {
                transform(
                    node,
                    node.repository,
                    transitiveClosure.map { it.toString() + ":" + it.repository }.toSet()
                )?.let(result::add)
            }
        }
        dfs(root)
        return result
    }

    companion object {
        private val IGNORED_ARTIFACTS = listOf(
            "org.jetbrains.kotlin:kotlin-parcelize-runtime"
        )
    }
}