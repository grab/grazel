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
        val leafVariants = leafNames.mapNotNull { leafName -> graph.variant(leafName) }
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

        val buildTypeBuckets = leafVariants
            .mapNotNull(MainBucketVariant::buildType)
            .toSortedSet()
            .mapNotNull { buildType ->
                val candidateDeps = candidateDepsFor(
                    bucketName = buildType,
                    graph = graph,
                    leafNames = leafNames,
                    leafClosures = leafClosures,
                    hierarchyBucketClosures = hierarchyBucketClosures
                )
                val deps = candidateDeps.withoutDependenciesCoveredBy(
                    defaultDeps.asCoveredBy(DEFAULT_VARIANT)
                )
                if (deps.isEmpty()) null else buildType to deps
            }
            .toMap()

        val buildTypeCoveredDeps = buildTypeBuckets.flatMap { (bucketName, deps) ->
            deps.asCoveredBy(bucketName)
        }
        val flavorBuckets = leafVariants
            .flatMap(MainBucketVariant::productFlavors)
            .toSortedSet()
            .mapNotNull { flavor ->
                val candidateDeps = candidateDepsFor(
                    bucketName = flavor,
                    graph = graph,
                    leafNames = leafNames,
                    leafClosures = leafClosures,
                    hierarchyBucketClosures = hierarchyBucketClosures
                )
                val deps = candidateDeps.withoutDependenciesCoveredBy(
                    defaultDeps.asCoveredBy(DEFAULT_VARIANT) + buildTypeCoveredDeps
                )
                if (deps.isEmpty()) null else flavor to deps
            }
            .toMap()

        val parentBucketsByName = buildTypeBuckets + flavorBuckets
        val leafBuckets = leafNames
            .mapNotNull { leafName ->
                val parentCoveredDeps = graph.parentsOf(leafName)
                    .filter { parentName -> parentName != DEFAULT_VARIANT }
                    .flatMap { parentName -> parentBucketsByName[parentName].orEmpty().asCoveredBy(parentName) }
                val deps = leafClosures.getValue(leafName).withoutDependenciesCoveredBy(
                    defaultDeps.asCoveredBy(DEFAULT_VARIANT) + parentCoveredDeps
                )
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
        val descendantLeafNames = leafNames
            .filter { leafName -> graph.hasAncestor(leafName, bucketName) }
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

    fun variant(name: String): MainBucketVariant? {
        return variantsByName[name]
    }

    fun parentsOf(name: String): Set<String> {
        return variantsByName[name]?.extendsFrom.orEmpty()
    }

    fun hasAncestor(name: String, ancestor: String): Boolean {
        return ancestor in ancestorsOf(name)
    }

    private fun ancestorsOf(name: String): Set<String> {
        val visited = linkedSetOf<String>()

        fun visit(bucketName: String) {
            val parents = variantsByName[bucketName]?.extendsFrom.orEmpty()
            parents.forEach { parent ->
                if (visited.add(parent)) {
                    visit(parent)
                }
            }
        }

        visit(name)
        return visited
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
