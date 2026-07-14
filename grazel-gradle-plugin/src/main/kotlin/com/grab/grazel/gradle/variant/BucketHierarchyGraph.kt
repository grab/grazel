/*
 * Copyright 2026 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.gradle.variant

internal data class BucketHierarchyNode(
    val projectPath: String,
    val name: String,
    val variantType: VariantType
)

internal data class BucketHierarchyEntry(
    val node: BucketHierarchyNode,
    val extendsFrom: Set<String>,
    val leaf: Boolean
)

private data class NodeNameKey(
    val projectPath: String,
    val name: String
)

/**
 * Immutable DAG of [BucketHierarchyNode]s (variant configuration buckets) linked by their
 * `extendsFrom` relationships. Ancestor and leaf-descendant closures are computed lazily and
 * cached per node since callers typically only query a subset of nodes.
 *
 * Iteration order (and therefore the order in which ancestors/descendants are reported) is always
 * normalized via [bucketHierarchyNodeComparator] rather than left to hash-map/insertion order, so
 * that downstream consumers (e.g. render-plan generation) get deterministic output across runs.
 */
internal class BucketHierarchyGraph private constructor(
    private val entriesByNode: Map<BucketHierarchyNode, BucketHierarchyEntry>,
    private val predecessorsByNode: Map<BucketHierarchyNode, Set<BucketHierarchyNode>>
) {
    private val ancestorsByNode: Map<BucketHierarchyNode, Set<BucketHierarchyNode>> by lazy {
        entriesByNode.keys
            .associateWith(::computeAncestorsOf)
            .let(::sortedBucketHierarchyNodeMap)
    }

    private val leafDescendantsByNode: Map<BucketHierarchyNode, Set<BucketHierarchyNode>> by lazy {
        val leafNodes = entriesByNode
            .values
            .asSequence()
            .filter(BucketHierarchyEntry::leaf)
            .map(BucketHierarchyEntry::node)
            .toList()
        val descendantsByNode = entriesByNode.keys
            .associateWith { linkedSetOf<BucketHierarchyNode>() }
            .toMutableMap()
        leafNodes.forEach { leaf ->
            ancestorsOf(leaf).forEach { ancestor ->
                descendantsByNode.getValue(ancestor).add(leaf)
            }
        }
        descendantsByNode
            .mapValues { (_, descendants) -> sortedBucketHierarchyNodes(descendants) }
            .let(::sortedBucketHierarchyNodeMap)
    }

    companion object {
        /**
         * Resolves each entry's predecessors by name-matching its declared `extendsFrom` names
         * against other nodes *within the same project only* (cross-project name collisions are
         * not linked). A same-named match is only accepted as a predecessor if
         * [BucketHierarchyNode.canExtendFrom] allows the pairing - this is what prevents, e.g., a
         * lint bucket from silently inheriting from an unrelated Android/JVM build bucket that
         * happens to share a name.
         */
        fun from(entries: Collection<BucketHierarchyEntry>): BucketHierarchyGraph {
            val entriesByNode = entries
                .associateBy(BucketHierarchyEntry::node)
                .let(::sortedBucketHierarchyNodeMap)
            val nodesByProjectAndName = entriesByNode.keys.groupBy { node ->
                NodeNameKey(node.projectPath, node.name)
            }
            val predecessorsByNode = entriesByNode.mapValues { (_, entry) ->
                entry.extendsFrom
                    .flatMap { parentName ->
                        nodesByProjectAndName[NodeNameKey(entry.node.projectPath, parentName)].orEmpty()
                    }
                    .filter { parent -> parent != entry.node }
                    .filter { parent -> entry.node.canExtendFrom(parent) }
                    .let(::sortedBucketHierarchyNodes)
            }.let(::sortedBucketHierarchyNodeMap)
            return BucketHierarchyGraph(
                entriesByNode = entriesByNode,
                predecessorsByNode = predecessorsByNode
            )
        }
    }

    fun ancestorsOf(node: BucketHierarchyNode): Set<BucketHierarchyNode> {
        return ancestorsByNode[node].orEmpty()
    }

    /**
     * DFS over [predecessorsByNode] guarded by a `visited` set so that any accidental cycle in
     * the extends-from edges terminates instead of recursing forever, and so a node reachable via
     * multiple paths is only visited/added once.
     */
    private fun computeAncestorsOf(node: BucketHierarchyNode): Set<BucketHierarchyNode> {
        val visited = linkedSetOf<BucketHierarchyNode>()

        fun visit(current: BucketHierarchyNode) {
            predecessorsByNode[current].orEmpty().forEach { predecessor ->
                if (visited.add(predecessor)) {
                    visit(predecessor)
                }
            }
        }

        visit(node)
        return sortedBucketHierarchyNodes(visited)
    }

    fun hasAncestor(node: BucketHierarchyNode, ancestor: BucketHierarchyNode): Boolean {
        return ancestor in ancestorsOf(node)
    }

    fun depthOf(node: BucketHierarchyNode): Int {
        return ancestorsOf(node).size
    }

    fun leafDescendantsOf(node: BucketHierarchyNode): Set<BucketHierarchyNode> {
        return leafDescendantsByNode[node].orEmpty()
    }

}

/**
 * Implicit rule table for which [VariantType] a bucket may extend from: build variants
 * (Android/JVM) never cross into each other or into test-ish types, [VariantType.Test] may extend
 * either build type as well as itself, [VariantType.AndroidTest] additionally extends
 * [VariantType.Test], and [VariantType.Lint] is self-contained. Getting an entry here wrong
 * silently mislinks buckets rather than failing loudly, since [from] just filters out
 * disallowed pairings.
 */
private fun BucketHierarchyNode.canExtendFrom(parent: BucketHierarchyNode): Boolean {
    return parent.variantType in when (variantType) {
        VariantType.AndroidBuild -> setOf(VariantType.AndroidBuild)
        VariantType.JvmBuild -> setOf(VariantType.JvmBuild)
        VariantType.Test -> setOf(VariantType.Test, VariantType.AndroidBuild, VariantType.JvmBuild)
        VariantType.AndroidTest -> setOf(
            VariantType.AndroidTest,
            VariantType.Test,
            VariantType.AndroidBuild,
            VariantType.JvmBuild
        )
        VariantType.Lint -> setOf(VariantType.Lint)
    }
}

private val bucketHierarchyNodeComparator = compareBy(
    BucketHierarchyNode::projectPath,
    { node -> node.variantType.name },
    BucketHierarchyNode::name
)

private fun sortedBucketHierarchyNodes(
    nodes: Iterable<BucketHierarchyNode>
): Set<BucketHierarchyNode> {
    return nodes.sortedWith(bucketHierarchyNodeComparator).toCollection(linkedSetOf())
}

private fun <V> sortedBucketHierarchyNodeMap(
    valuesByNode: Map<BucketHierarchyNode, V>
): Map<BucketHierarchyNode, V> {
    return valuesByNode.toSortedMap(bucketHierarchyNodeComparator)
}
