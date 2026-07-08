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

import com.android.build.gradle.internal.attributes.VariantAttr
import com.grab.grazel.gradle.dependencies.ResolvedComponentsVisitor.Companion.IGNORED_ARTIFACTS
import com.grab.grazel.util.ansiCyan
import com.grab.grazel.util.ansiGreen
import com.grab.grazel.util.ansiYellow
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.internal.artifacts.result.DefaultResolvedComponentResult

private typealias ResolvedComponentNode = ResolvedComponentResult
private typealias DefaultResolvedComponentNode = DefaultResolvedComponentResult

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

    private val ResolvedComponentNode.isProject get() = toString().startsWith("project :")
    private val ResolvedComponentNode.repository get() = (this as? DefaultResolvedComponentNode)?.repositoryName ?: ""
    private val ResolvedComponentNode.shortId get() = toString().substringBeforeLast(":")

    private val ComponentSelector.isLegacySupportLibrary
        get() = toString().startsWith("com.android.support")

    data class VisitResult(
        val component: ResolvedComponentNode,
        val repository: String,
        val transitiveDeps: Set<DependencyResult>,
        val requiresJetifier: Boolean,
        /**
         * Set to `true` only when [visit] is called with `traverseProjectNodes = true` and this
         * external dep is an immediate child of either the resolved root or a project node in the
         * graph. This corresponds to the "direct" notion in per-module resolution where a dep is
         * "direct" iff it is a first-level dependency of the resolved configuration (not just
         * transitively reachable). Always `false` in the default (non-traversing) mode.
         */
        val directFromProject: Boolean = false,
        /**
         * Project path of the configuration owner that declared this direct external dependency.
         * This is populated only when [directFromProject] is true and the owning Gradle project can
         * be inferred from the resolved component graph.
         */
        val directProjectPath: String? = null,
        /**
         * Gradle selected variant display name for the project edge that owns this direct external
         * dependency. This stays raw because AGP/Gradle names are configuration-like strings such
         * as `paidDebugRuntimeElements`.
         */
        val directProjectVariantDisplayName: String? = null,
        /**
         * The AGP [VariantAttr] name stamped on the resolved variant for the project edge that owns
         * this direct external dependency. This is the typed attribute value (e.g. "paidDebug")
         * and serves as a direct key into `variantHierarchyNamesByName`. Null for JVM libraries and
         * any component that does not carry the AGP variant attribute.
         */
        val directProjectVariantName: String? = null
    ) : Comparable<VisitResult> {
        override fun compareTo(
            other: VisitResult
        ) = component.toString().compareTo(other.component.toString())
    }

    data class DependencyResult(
        val dependency: ResolvedComponentNode,
        val requiresJetifier: Boolean,
        val unjetifiedSource: String?
    ) : Comparable<DependencyResult> {
        override fun compareTo(
            other: DependencyResult
        ) = dependency.toString().compareTo(other.dependency.toString())
    }

    private data class DependencyTraversalResult(
        val dependencies: Set<DependencyResult>,
        val requiresJetifier: Boolean
    )

    private val ResolvedComponentNode.projectPath: String?
        get() = toString()
            .takeIf { it.startsWith("project :") }
            ?.substringAfter("project ")

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
     *   original behaviour for single-project roots.
     * @param transform The callback used to convert to [T]
     */
    fun <T : Comparable<T>> visit(
        root: ResolvedComponentNode,
        logger: (message: String) -> Unit = { },
        traverseProjectNodes: Boolean = false,
        transform: (visitResult: VisitResult) -> T?
    ): Set<T> {
        val dfsResults = hashMapOf<ResolvedComponentNode, DependencyTraversalResult>()
        val visited = hashSetOf<ResolvedComponentNode>()
        val result = sortedSetOf<T>()

        fun emit(
            node: ResolvedComponentNode,
            dfsResult: DependencyTraversalResult,
            directProjectPath: String?,
            directProjectVariantDisplayName: String?,
            directProjectVariantName: String?
        ) {
            if (!node.isProject) {
                transform(
                    VisitResult(
                        component = node,
                        repository = node.repository,
                        transitiveDeps = dfsResult.dependencies,
                        requiresJetifier = dfsResult.requiresJetifier,
                        directFromProject = traverseProjectNodes && directProjectPath != null,
                        directProjectPath = directProjectPath,
                        directProjectVariantDisplayName = directProjectVariantDisplayName,
                        directProjectVariantName = directProjectVariantName
                    )
                )?.let(result::add)
            }
        }

        data class ChildDependency(
            val selected: ResolvedComponentNode,
            val requested: ComponentSelector,
            val constraint: Boolean,
            val selectedVariantDisplayName: String,
            val selectedVariantAttrName: String?
        )

        /**
         * Do a depth-first visit to collect all transitive dependencies and track jetifier
         * requirements.
         *
         * @param node Current node being visited.
         * @param level Indentation level for logging.
         * @param parentIsDirect When [traverseProjectNodes] is `true`, indicates that the direct
         *   parent of [node] was either the resolved root or a project node. External deps with such
         *   a parent are first-level dependencies from the aggregated perspective and should be
         *   emitted with [VisitResult.directFromProject] = `true`.
         */
        fun dfs(
            node: ResolvedComponentNode,
            level: Int = 0,
            directProjectPath: String? = null,
            directProjectVariantDisplayName: String? = null,
            directProjectVariantName: String? = null,
            projectVariantDisplayName: String? = null,
            projectVariantAttrName: String? = null
        ): DependencyTraversalResult {
            if (node in visited) {
                val cachedResult = dfsResults[node] ?: return DependencyTraversalResult(emptySet(), false)
                emit(node, cachedResult, directProjectPath, directProjectVariantDisplayName, directProjectVariantName)
                return cachedResult
            }
            visited.add(node)
            printIndented(level, node.toString(), logger)

            var requiresJetifier = false
            val allDependencies = hashSetOf<DependencyResult>()

            node.dependencies
                .asSequence()
                .filterIsInstance<ResolvedDependencyResult>()
                .map {
                    ChildDependency(
                        selected = it.selected,
                        requested = it.requested,
                        constraint = it.isConstraint,
                        selectedVariantDisplayName = it.resolvedVariant.displayName,
                        selectedVariantAttrName = it.resolvedVariant.attributes
                            .getAttribute(VariantAttr.ATTRIBUTE)?.name
                    )
                }
                .let { seq ->
                    if (traverseProjectNodes) seq // keep project nodes so we can descend through them
                    else seq.filter { (selected, _, _) -> !selected.isProject }
                }
                .filter { (dep, _, _) -> IGNORED_ARTIFACTS.none { dep.toString().startsWith(it) } }
                .forEach { (child, requested, constraint, selectedVariantDisplayName, selectedVariantAttrName) ->
                    if (traverseProjectNodes && child.isProject) {
                        // Descend through the project node. Its external children are handled as
                        // direct when that project node becomes their parent below.
                        val childResult = dfs(
                            child,
                            level + 1,
                            projectVariantDisplayName = selectedVariantDisplayName,
                            projectVariantAttrName = selectedVariantAttrName
                        )
                        allDependencies.addAll(childResult.dependencies)
                        requiresJetifier = requiresJetifier || childResult.requiresJetifier
                    } else {
                        if (constraint) return@forEach

                        // First-level external children can hang directly off the resolved root
                        // (for configuration-level deps) or under a traversed project node.
                        val childDirectProjectPath = if (traverseProjectNodes) {
                            when {
                                node.isProject -> node.projectPath
                                level == 0 -> root.projectPath
                                else -> null
                            }
                        } else {
                            null
                        }
                        val childResult = dfs(
                            child,
                            level + 1,
                            directProjectPath = childDirectProjectPath,
                            directProjectVariantDisplayName = projectVariantDisplayName,
                            directProjectVariantName = projectVariantAttrName
                        )
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

            val dfsResult = DependencyTraversalResult(allDependencies, requiresJetifier)
            dfsResults[node] = dfsResult

            emit(node, dfsResult, directProjectPath, directProjectVariantDisplayName, directProjectVariantName)
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
