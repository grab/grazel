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

private typealias Node = ResolvedComponentResult
private typealias DefaultNode = DefaultResolvedComponentResult

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
    private val Node.repository get() = (this as? DefaultNode)?.repositoryName ?: ""
    private val Node.shortId get() = toString().substringBeforeLast(":")

    private val ComponentSelector.isLegacySupportLibrary
        get() = toString().startsWith("com.android.support")

    data class VisitResult(
        val component: Node,
        val repository: String,
        val transitiveDeps: Set<DependencyResult>,
        val requiresJetifier: Boolean,
        /**
         * Set to `true` only when [visit] is called with `traverseProjectNodes = true` and this
         * external dep is an immediate child of a project node in the graph. This corresponds to
         * the "direct" notion in per-module resolution where a dep is "direct" iff it is declared
         * directly in the module's compile configuration (not just transitively reachable).
         * Always `false` in the default (non-traversing) mode.
         */
        val directFromProject: Boolean = false
    ) : Comparable<VisitResult> {
        override fun compareTo(
            other: VisitResult
        ) = component.toString().compareTo(other.component.toString())
    }

    /** Holder to collect dependency information for a [Node] */
    data class DependencyResult(
        val dependency: Node,
        val requiresJetifier: Boolean,
        val unjetifiedSource: String?
    ) : Comparable<DependencyResult> {
        override fun compareTo(
            other: DependencyResult
        ) = dependency.toString().compareTo(other.dependency.toString())
    }

    private data class DFSResult(
        val dependencies: Set<DependencyResult>,
        val requiresJetifier: Boolean
    )

    /**
     * Visit all external dependency nodes in the graph and map them to [T] using the [transform]
     * function. Both current component and its transitive dependencies are provided in the callback
     *
     * @param root The root component usually [ResolutionResult.getRoot]
     * @param logger The logger to print traversal information in tree format
     * @param traverseProjectNodes When `true`, project nodes are descended through (not emitted)
     *   so that their external-dependency children are reachable. An external dep whose immediate
     *   parent in the graph is a project node is considered a **direct** dependency (the caller
     *   receives it via [VisitResult.directFromProject] = `true`). This mode is used by
     *   [AggregatedDependencyResolver] where the graph root is the Gradle root project and the
     *   sub-projects are intermediate project nodes between the root and the real external deps.
     *   When `false` (the default), project nodes are filtered out before recursion — this is the
     *   original behaviour used by per-module resolution in
     *   [com.grab.grazel.tasks.internal.ResolveVariantDependenciesTask].
     * @param transform The callback used to convert to [T]
     */
    fun <T : Comparable<T>> visit(
        root: Node,
        logger: (message: String) -> Unit = { },
        traverseProjectNodes: Boolean = false,
        transform: (visitResult: VisitResult) -> T?
    ): Set<T> {
        val dfsResults = hashMapOf<Node, DFSResult>()
        val visited = hashSetOf<Node>()
        val result = sortedSetOf<T>()

        /**
         * Do a depth-first visit to collect all transitive dependencies and track jetifier
         * requirements.
         *
         * @param node Current node being visited.
         * @param level Indentation level for logging.
         * @param parentIsProject When [traverseProjectNodes] is `true`, indicates that the direct
         *   parent of [node] was a project node. External deps that are direct children of a project
         *   node are "direct" from the aggregated perspective and should be emitted with
         *   [VisitResult.directFromProject] = `true`.
         */
        fun dfs(node: Node, level: Int = 0, parentIsProject: Boolean = false): DFSResult {
            if (node in visited) {
                return dfsResults[node] ?: DFSResult(emptySet(), false)
            }
            visited.add(node)
            printIndented(level, node.toString(), logger)

            var requiresJetifier = false
            val allDependencies = hashSetOf<DependencyResult>()

            node.dependencies
                .asSequence()
                .filterIsInstance<ResolvedDependencyResult>()
                .map { it.selected to it.requested }
                .let { seq ->
                    if (traverseProjectNodes) seq // keep project nodes so we can descend through them
                    else seq.filter { (selected, _) -> !selected.isProject }
                }
                .filter { (dep, _) -> IGNORED_ARTIFACTS.none { dep.toString().startsWith(it) } }
                .forEach { (child, requested) ->
                    if (traverseProjectNodes && child.isProject) {
                        // Descend through the project node — its external children are "direct"
                        val childResult = dfs(child, level + 1, parentIsProject = true)
                        allDependencies.addAll(childResult.dependencies)
                        requiresJetifier = requiresJetifier || childResult.requiresJetifier
                    } else {
                        val childResult = dfs(child, level + 1, parentIsProject = false)
                        val directDepResult = DependencyResult(
                            dependency = child,
                            requiresJetifier = requested.isLegacySupportLibrary,
                            unjetifiedSource = JetifiedArtifacts[child.shortId]
                        )
                        allDependencies.add(directDepResult)
                        allDependencies.addAll(childResult.dependencies)

                        requiresJetifier = requiresJetifier ||
                            directDepResult.requiresJetifier ||
                            childResult.requiresJetifier
                    }
                }

            val dfsResult = DFSResult(allDependencies, requiresJetifier)
            dfsResults[node] = dfsResult

            if (!node.isProject) {
                transform(
                    VisitResult(
                        component = node,
                        repository = node.repository,
                        transitiveDeps = allDependencies,
                        requiresJetifier = requiresJetifier,
                        directFromProject = traverseProjectNodes && parentIsProject
                    )
                )?.let(result::add)
            }
            return dfsResult
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