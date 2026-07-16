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

package com.grab.grazel.gradle.dependencies.bucket

import com.grab.grazel.gradle.variant.BucketHierarchyEntry
import com.grab.grazel.gradle.variant.BucketHierarchyGraph
import com.grab.grazel.gradle.variant.BucketHierarchyNode
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild

/**
 * Wraps [BucketHierarchyGraph] with the specific view [DependencyBucketPlacementEngine.plan] needs:
 * a graph that includes not just the declared [variants] but also any bucket name referenced only
 * as a parent/ancestor (via `extendsFrom`) or as [baseBucketName] - these "synthetic" nodes are
 * added so ancestor/depth queries work uniformly even for buckets that were never themselves
 * declared as a variant (e.g. an implied flavor grouping bucket). Ancestor and descendant-leaf name
 * sets are the two properties placement leans on repeatedly for its coverage checks, so both are
 * computed lazily and cached per bucket name rather than walking the graph on every query.
 */
internal class BucketPlacementGraph(
    variants: Collection<BucketPlacementVariantInput>,
    private val baseBucketName: String
) {
    private val leafVariants = variants.filter(BucketPlacementVariantInput::leaf)
    val leafVariantNames = leafVariants.map(BucketPlacementVariantInput::name).toSortedSet()
    val variantTypesByBucketName = variants
        .associate { variant -> variant.name to variant.variantType }
        .toSortedMap()
    private val parentNames = variants
        .flatMap(BucketPlacementVariantInput::extendsFrom)
        .toSortedSet()
    private val projectPath = variants.firstOrNull()?.projectPath.orEmpty()
    private val defaultVariantType = variants.firstOrNull()?.variantType ?: AndroidBuild
    val hierarchyBucketNames = (
        variants.filterNot(BucketPlacementVariantInput::leaf).map(BucketPlacementVariantInput::name) +
            parentNames +
            baseBucketName
    )
        .toSortedSet()
    val bucketNames: Set<String>
        get() = nodeByName.keys
    private lateinit var nodeByName: Map<String, BucketHierarchyNode>
    private lateinit var graph: BucketHierarchyGraph
    private val ancestorNamesByName: Map<String, Set<String>> by lazy(LazyThreadSafetyMode.NONE) {
        nodeByName.keys
            .associateWith { bucketName ->
                graph.ancestorsOf(nodeFor(bucketName)).mapTo(sortedSetOf(), BucketHierarchyNode::name)
            }
            .toSortedMap()
    }
    private val leafDescendantNamesByName: Map<String, Set<String>> by lazy(LazyThreadSafetyMode.NONE) {
        nodeByName.keys
            .associateWith { bucketName ->
                val bucketNode = nodeFor(bucketName)
                (
                    graph.leafDescendantsOf(bucketNode).map(BucketHierarchyNode::name) +
                        listOf(bucketName).filter { leafName -> leafName in leafVariantNames }
                    )
                    .toSortedSet()
            }
            .toSortedMap()
    }

    init {
        val variantEntries = variants.map { variant ->
            BucketHierarchyEntry(
                node = variant.node(),
                extendsFrom = variant.extendsFrom,
                leaf = variant.leaf
            )
        }
        val variantNames = variants.map(BucketPlacementVariantInput::name).toSet()
        val syntheticNames = hierarchyBucketNames
            .filter { bucketName -> bucketName !in variantNames }
            .toSortedSet()
        val syntheticEntries = syntheticNames.map { bucketName ->
            BucketHierarchyEntry(
                node = BucketHierarchyNode(
                    projectPath = projectPath,
                    name = bucketName,
                    variantType = defaultVariantType
                ),
                extendsFrom = if (bucketName == baseBucketName || bucketName == DEFAULT_VARIANT) {
                    emptySet()
                } else {
                    setOf(baseBucketName)
                },
                leaf = false
            )
        }
        val entries = variantEntries + syntheticEntries
        nodeByName = entries.associate { entry -> entry.node.name to entry.node }
        graph = BucketHierarchyGraph.from(entries)
    }

    fun hasAncestor(name: String, ancestor: String): Boolean {
        return ancestor in ancestorsOf(name)
    }

    fun ancestorsOf(name: String): Set<String> {
        return ancestorNamesByName[name].orEmpty()
    }

    fun depthOf(name: String): Int {
        return ancestorsOf(name).size
    }

    fun descendantLeafNames(bucketName: String, leafNames: Set<String>): Set<String> {
        return leafDescendantNamesByName[bucketName]
            .orEmpty()
            .filter { leafName -> leafName in leafNames }
            .toSortedSet()
    }

    private fun BucketPlacementVariantInput.node(): BucketHierarchyNode {
        return BucketHierarchyNode(
            projectPath = projectPath,
            name = name,
            variantType = variantType
        )
    }

    private fun nodeFor(bucketName: String): BucketHierarchyNode {
        return nodeByName[bucketName] ?: BucketHierarchyNode(
            projectPath = projectPath,
            name = bucketName,
            variantType = defaultVariantType
        )
    }
}
