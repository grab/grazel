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

package com.grab.grazel.gradle.dependencies

import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild

internal data class MainBucketVariant(
    val name: String,
    val extendsFrom: Set<String>,
    val buildType: String?,
    val productFlavors: List<String>,
    val leaf: Boolean
)

internal data class MainDependencyBucketPlan(
    val defaultBucket: Map<String, ResolvedDependency>,
    val buildTypeBuckets: Map<String, Map<String, ResolvedDependency>>,
    val flavorBuckets: Map<String, Map<String, ResolvedDependency>>,
    val leafBuckets: Map<String, Map<String, ResolvedDependency>>
) {
    fun coveredDependencies(): List<CoveredDependency> {
        return buildList {
            addAll(defaultBucket.asCoveredBy(DEFAULT_VARIANT))
            buildTypeBuckets.forEach { (bucketName, deps) -> addAll(deps.asCoveredBy(bucketName)) }
            flavorBuckets.forEach { (bucketName, deps) -> addAll(deps.asCoveredBy(bucketName)) }
            leafBuckets.forEach { (bucketName, deps) -> addAll(deps.asCoveredBy(bucketName)) }
        }
    }
}

internal class MainDependencyBucketPlanner {
    fun plan(
        variants: Collection<MainBucketVariant>,
        hierarchyBucketClosures: Map<String, Map<String, ResolvedDependency>>,
        leafClosures: Map<String, Map<String, ResolvedDependency>>
    ): MainDependencyBucketPlan {
        val graph = MainBucketGraph(variants)
        val leafNames = leafClosures.keys.sorted()
        val hierarchyDefaultDeps = hierarchyBucketClosures[DEFAULT_VARIANT].orEmpty()
        val nonDefaultHierarchyDeps = hierarchyBucketClosures
            .filterKeys { bucketName -> bucketName != DEFAULT_VARIANT }
            .values
            .flatMap { deps -> deps.values }
        val defaultDeps = (
            intersectByBucketOwner(leafNames.mapNotNull { leafName -> leafClosures[leafName] }) +
                hierarchyDefaultDeps
            )
            .withoutDependenciesOwnedByNonDefaultHierarchy(
                hierarchyDefaultDeps = hierarchyDefaultDeps,
                nonDefaultHierarchyDependencies = nonDefaultHierarchyDeps
            )

        val selectedHierarchyBuckets = linkedMapOf<String, Map<String, ResolvedDependency>>()
        val defaultCoveredDeps = defaultDeps.asCoveredBy(DEFAULT_VARIANT)

        fun selectedCoveredDepsFor(bucketName: String): List<CoveredDependency> {
            return selectedHierarchyBuckets
                .filterKeys { selectedBucketName ->
                    graph.hasAncestor(bucketName, selectedBucketName) ||
                        graph.hasAncestor(selectedBucketName, bucketName) ||
                        graph.coversDescendantLeaves(
                            coveringBucketName = selectedBucketName,
                            coveredBucketName = bucketName,
                            leafNames = leafNames
                        )
                }
                .flatMap { (selectedBucketName, deps) -> deps.asCoveredBy(selectedBucketName) }
        }

        val explicitBucketNames = hierarchyBucketClosures
            .keys
            .filter { bucketName -> bucketName != DEFAULT_VARIANT }
            .sortedWith(compareByDescending<String> { bucketName -> graph.depthOf(bucketName) }.thenBy { it })

        explicitBucketNames.forEach { bucketName ->
            val deps = hierarchyBucketClosures
                .getValue(bucketName)
                .withoutDependenciesCoveredBy(
                    defaultCoveredDeps + selectedCoveredDepsFor(bucketName)
                )
            if (deps.isNotEmpty()) {
                selectedHierarchyBuckets[bucketName] = deps
            }
        }

        val inferredBucketNames = graph.hierarchyBucketNames
            .filter { bucketName -> bucketName != DEFAULT_VARIANT && bucketName !in hierarchyBucketClosures }
            .sortedWith(
                compareByDescending<String> { bucketName ->
                    graph.descendantLeafNames(bucketName, leafNames).size
                }
                    .thenByDescending { bucketName -> graph.depthOf(bucketName) }
                    .thenBy { bucketName -> bucketName }
            )

        inferredBucketNames.forEach { bucketName ->
            val candidateDeps = candidateDepsFor(
                bucketName = bucketName,
                graph = graph,
                leafNames = leafNames,
                leafClosures = leafClosures,
                hierarchyBucketClosures = hierarchyBucketClosures
            )
            val deps = candidateDeps.withoutDependenciesCoveredBy(
                defaultCoveredDeps + selectedCoveredDepsFor(bucketName)
            )
            if (deps.isNotEmpty()) {
                selectedHierarchyBuckets[bucketName] = deps
            }
        }

        val buildTypeBuckets = selectedHierarchyBuckets
            .filterKeys { bucketName -> bucketName in graph.buildTypeNames }
            .toMap()
        val flavorBuckets = selectedHierarchyBuckets
            .filterKeys { bucketName -> bucketName in graph.flavorNames }
            .toMap()
        val selectedLeafBuckets = selectedHierarchyBuckets
            .filterKeys { bucketName -> bucketName in graph.leafVariantNames }
        val outputLeafNames = (leafNames + selectedLeafBuckets.keys).toSortedSet()
        val leafBuckets = outputLeafNames
            .mapNotNull { leafName ->
                val ancestorCoveredDeps = graph.ancestorsOf(leafName)
                    .filter { parentName -> parentName != DEFAULT_VARIANT }
                    .flatMap { parentName ->
                        selectedHierarchyBuckets[parentName].orEmpty().asCoveredBy(parentName)
                    }
                val selectedLeafDeps = selectedLeafBuckets[leafName].orEmpty()
                val residualDeps = leafClosures[leafName]
                    .orEmpty()
                    .withoutDependenciesCoveredBy(
                        defaultCoveredDeps +
                            ancestorCoveredDeps +
                            selectedLeafDeps.asCoveredBy(leafName)
                    )
                val deps = residualDeps + selectedLeafDeps
                if (deps.isEmpty()) null else leafName to deps
            }
            .toMap()

        return MainDependencyBucketPlan(
            defaultBucket = defaultDeps,
            buildTypeBuckets = buildTypeBuckets,
            flavorBuckets = flavorBuckets,
            leafBuckets = leafBuckets
        )
    }

    private fun candidateDepsFor(
        bucketName: String,
        graph: MainBucketGraph,
        leafNames: Collection<String>,
        leafClosures: Map<String, Map<String, ResolvedDependency>>,
        hierarchyBucketClosures: Map<String, Map<String, ResolvedDependency>>
    ): Map<String, ResolvedDependency> {
        hierarchyBucketClosures[bucketName]?.let { deps -> return deps }
        val descendantLeafNames = graph.descendantLeafNames(bucketName, leafNames)
        if (descendantLeafNames.size < 2) return emptyMap()
        return intersectByBucketOwner(
            descendantLeafNames.mapNotNull { leafName -> leafClosures[leafName] }
        )
    }
}

private class MainBucketGraph(
    variants: Collection<MainBucketVariant>
) {
    private val variantsByName = variants.associateBy(MainBucketVariant::name)
    private val leafVariants = variants.filter(MainBucketVariant::leaf)
    val leafVariantNames = leafVariants.map(MainBucketVariant::name).toSortedSet()
    val buildTypeNames = leafVariants.mapNotNull(MainBucketVariant::buildType).toSortedSet()
    val flavorNames = leafVariants.flatMap(MainBucketVariant::productFlavors).toSortedSet()
    val hierarchyBucketNames = (
        variants.filterNot(MainBucketVariant::leaf).map(MainBucketVariant::name) +
            buildTypeNames +
            flavorNames +
            DEFAULT_VARIANT
        )
        .toSortedSet()
    private val parentsByName = buildMap<String, Set<String>> {
        variants.forEach { variant ->
            put(variant.name, variant.extendsFrom)
        }
        variants.flatMap(MainBucketVariant::extendsFrom).forEach { parentName ->
            putIfAbsent(
                parentName,
                if (parentName == DEFAULT_VARIANT) emptySet() else setOf(DEFAULT_VARIANT)
            )
        }
        hierarchyBucketNames.forEach { bucketName ->
            putIfAbsent(
                bucketName,
                if (bucketName == DEFAULT_VARIANT) emptySet() else setOf(DEFAULT_VARIANT)
            )
        }
    }

    fun variant(name: String): MainBucketVariant? {
        return variantsByName[name]
    }

    fun hasAncestor(name: String, ancestor: String): Boolean {
        return ancestor in ancestorsOf(name)
    }

    fun ancestorsOf(name: String): Set<String> {
        val visited = linkedSetOf<String>()

        fun visit(bucketName: String) {
            val parents = parentsByName[bucketName].orEmpty()
            parents.forEach { parent ->
                if (visited.add(parent)) {
                    visit(parent)
                }
            }
        }

        visit(name)
        return visited
    }

    fun depthOf(name: String): Int {
        return ancestorsOf(name).size
    }

    fun descendantLeafNames(bucketName: String, leafNames: Collection<String>): List<String> {
        return leafNames
            .filter { leafName -> hasAncestor(leafName, bucketName) }
            .sorted()
    }

    fun coversDescendantLeaves(
        coveringBucketName: String,
        coveredBucketName: String,
        leafNames: Collection<String>
    ): Boolean {
        val coveredLeaves = descendantLeafNames(coveredBucketName, leafNames)
        if (coveredLeaves.isEmpty()) return false
        return descendantLeafNames(coveringBucketName, leafNames).containsAll(coveredLeaves)
    }
}

internal fun DeclaredDependencyMetadata.mainBucketVariants(): List<MainBucketVariant> {
    return projects
        .values
        .flatMap(ProjectDeclaredDependencyMetadata::variants)
        .asSequence()
        .filter { variant -> variant.variantType == AndroidBuild }
        .map { variant ->
            MainBucketVariant(
                name = variant.name,
                extendsFrom = variant.extendsFrom,
                buildType = variant.buildType,
                productFlavors = variant.productFlavors,
                leaf = variant.androidLeafVariant
            )
        }
        .distinctBy(MainBucketVariant::name)
        .sortedBy(MainBucketVariant::name)
        .toList()
}

internal fun Map<String, ResolvedDependency>.asCoveredBy(bucketName: String): List<CoveredDependency> {
    return values.map { dependency -> CoveredDependency(bucketName, dependency) }
}
