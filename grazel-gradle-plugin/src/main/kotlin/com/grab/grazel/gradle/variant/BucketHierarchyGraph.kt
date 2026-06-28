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

internal class BucketHierarchyGraph private constructor(
    private val entriesByNode: Map<BucketHierarchyNode, BucketHierarchyEntry>,
    private val predecessorsByNode: Map<BucketHierarchyNode, Set<BucketHierarchyNode>>
) {
    private val ancestorsByNode: Map<BucketHierarchyNode, Set<BucketHierarchyNode>> by lazy {
        entriesByNode.keys
            .associateWith(::computeAncestorsOf)
            .toSortedNodeMap()
    }

    private val leafDescendantsByNode: Map<BucketHierarchyNode, Set<BucketHierarchyNode>> by lazy {
        val leafNodes = entriesByNode
            .values
            .asSequence()
            .filter(BucketHierarchyEntry::leaf)
            .map(BucketHierarchyEntry::node)
            .toList()
        entriesByNode.keys
            .associateWith { node ->
                leafNodes
                    .filter { leaf -> node in ancestorsOf(leaf) }
                    .toSortedNodeSet()
            }
            .toSortedNodeMap()
    }

    companion object {
        fun from(entries: Collection<BucketHierarchyEntry>): BucketHierarchyGraph {
            val entriesByNode = entries
                .associateBy(BucketHierarchyEntry::node)
                .toSortedNodeMap()
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
                    .toSortedNodeSet()
            }.toSortedNodeMap()
            return BucketHierarchyGraph(
                entriesByNode = entriesByNode,
                predecessorsByNode = predecessorsByNode
            )
        }
    }

    fun ancestorsOf(node: BucketHierarchyNode): Set<BucketHierarchyNode> {
        return ancestorsByNode[node].orEmpty()
    }

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
        return visited.toSortedNodeSet()
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

private data class NodeNameKey(
    val projectPath: String,
    val name: String
)

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

private val bucketHierarchyNodeComparator = compareBy<BucketHierarchyNode>(
    BucketHierarchyNode::projectPath,
    { node -> node.variantType.name },
    BucketHierarchyNode::name
)

private fun Iterable<BucketHierarchyNode>.toSortedNodeSet(): Set<BucketHierarchyNode> {
    return sortedWith(bucketHierarchyNodeComparator).toCollection(linkedSetOf())
}

private fun <V> Map<BucketHierarchyNode, V>.toSortedNodeMap(): Map<BucketHierarchyNode, V> {
    return toSortedMap(bucketHierarchyNodeComparator)
}
