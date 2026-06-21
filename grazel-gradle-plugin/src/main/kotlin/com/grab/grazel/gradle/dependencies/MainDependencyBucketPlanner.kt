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
import com.grab.grazel.gradle.dependencies.model.merge
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.testSuffix

internal data class MainBucketVariant(
    val name: String,
    val extendsFrom: Set<String>,
    val buildType: String?,
    val productFlavors: List<String>,
    val leaf: Boolean,
    val projectPath: String = ""
)

internal data class MainDependencyBucketPlan(
    val baseBucketName: String = DEFAULT_VARIANT,
    val defaultBucket: Map<String, ResolvedDependency>,
    val hierarchyBuckets: Map<String, Map<String, ResolvedDependency>>,
    val buildTypeBuckets: Map<String, Map<String, ResolvedDependency>>,
    val flavorBuckets: Map<String, Map<String, ResolvedDependency>>,
    val leafBuckets: Map<String, Map<String, ResolvedDependency>>
) {
    fun coveredDependencies(): List<CoveredDependency> {
        return buildList {
            addAll(defaultBucket.asCoveredBy(baseBucketName))
            hierarchyBuckets.forEach { (bucketName, deps) -> addAll(deps.asCoveredBy(bucketName)) }
            leafBuckets.forEach { (bucketName, deps) -> addAll(deps.asCoveredBy(bucketName)) }
        }
    }
}

internal class MainDependencyBucketPlanner {
    fun planByProject(
        variants: Collection<MainBucketVariant>,
        hierarchyBucketClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        leafClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        baseBucketName: String = DEFAULT_VARIANT
    ): Map<String, MainDependencyBucketPlan> {
        val projectPaths = (
            variants.map(MainBucketVariant::projectPath) +
                hierarchyBucketClosures.keys.map(ProjectDependencyBucket::projectPath) +
                leafClosures.keys.map(ProjectDependencyBucket::projectPath)
            )
            .filter { projectPath -> projectPath.isNotBlank() }
            .toSortedSet()

        return projectPaths.associateWith { projectPath ->
            plan(
                variants = variants.filter { variant -> variant.projectPath == projectPath },
                hierarchyBucketClosures = hierarchyBucketClosures
                    .filterKeys { bucket -> bucket.projectPath == projectPath }
                    .mapKeys { (bucket, _) -> bucket.bucketName },
                leafClosures = leafClosures
                    .filterKeys { bucket -> bucket.projectPath == projectPath }
                    .mapKeys { (bucket, _) -> bucket.bucketName },
                baseBucketName = baseBucketName
            )
        }
    }

    fun plan(
        variants: Collection<MainBucketVariant>,
        hierarchyBucketClosures: Map<String, Map<String, ResolvedDependency>>,
        leafClosures: Map<String, Map<String, ResolvedDependency>>,
        baseBucketName: String = DEFAULT_VARIANT
    ): MainDependencyBucketPlan {
        val graph = MainBucketGraph(variants, baseBucketName)
        val leafNames = leafClosures.keys.sorted()
        val hierarchyDefaultDeps = hierarchyBucketClosures[baseBucketName].orEmpty()
        val nonDefaultHierarchyDeps = hierarchyBucketClosures
            .filterKeys { bucketName -> bucketName != baseBucketName }
            .values
            .flatMap { deps -> deps.values }
        val inferredDefaultDeps = if (leafNames.size > 1) {
            intersectByBucketOwner(leafNames.mapNotNull { leafName -> leafClosures[leafName] })
        } else {
            emptyMap()
        }
        val fullCoverageHierarchyDeps = graph.hierarchyBucketNames
            .filter { bucketName ->
                bucketName != baseBucketName && graph.coversSelectedLeaves(
                    bucketName = bucketName,
                    leafNames = leafNames
                )
            }
            .flatMap { bucketName ->
                candidateDepsFor(
                    bucketName = bucketName,
                    graph = graph,
                    leafNames = leafNames,
                    leafClosures = leafClosures,
                    hierarchyBucketClosures = hierarchyBucketClosures
                ).values
            }
        val defaultDeps = (
            inferredDefaultDeps +
                hierarchyDefaultDeps
            )
            .withoutDependenciesOwnedByNonDefaultHierarchy(
                hierarchyDefaultDeps = hierarchyDefaultDeps,
                nonDefaultHierarchyDependencies = nonDefaultHierarchyDeps + fullCoverageHierarchyDeps
            )

        val selectedHierarchyBuckets = linkedMapOf<String, Map<String, ResolvedDependency>>()
        val defaultCoveredDeps = defaultDeps.asCoveredBy(baseBucketName)

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
            .filter { bucketName -> bucketName != baseBucketName }
            .sortedWith(compareByDescending<String> { bucketName -> graph.depthOf(bucketName) }.thenBy { it })

        explicitBucketNames.forEach { bucketName ->
            val deps = candidateDepsFor(
                bucketName = bucketName,
                graph = graph,
                leafNames = leafNames,
                leafClosures = leafClosures,
                hierarchyBucketClosures = hierarchyBucketClosures
            )
                .withoutDependenciesCoveredBy(
                    defaultCoveredDeps + selectedCoveredDepsFor(bucketName)
                )
            if (deps.isNotEmpty()) {
                selectedHierarchyBuckets[bucketName] = deps
            }
        }

        val inferredBucketNames = graph.hierarchyBucketNames
            .filter { bucketName -> bucketName != baseBucketName && bucketName !in hierarchyBucketClosures }
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

        val hierarchyBuckets = selectedHierarchyBuckets
            .filterKeys { bucketName -> bucketName !in graph.leafVariantNames }
            .toMap()
        val buildTypeBuckets = hierarchyBuckets
            .filterKeys { bucketName -> bucketName in graph.buildTypeNames }
            .toMap()
        val flavorBuckets = hierarchyBuckets
            .filterKeys { bucketName -> bucketName in graph.flavorNames }
            .toMap()
        val selectedLeafBuckets = selectedHierarchyBuckets
            .filterKeys { bucketName -> bucketName in graph.leafVariantNames }
        val outputLeafNames = (leafNames + selectedLeafBuckets.keys).toSortedSet()
        val leafBuckets = outputLeafNames
            .mapNotNull { leafName ->
                val ancestorCoveredDeps = graph.ancestorsOf(leafName)
                    .filter { parentName -> parentName != baseBucketName }
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
            baseBucketName = baseBucketName,
            defaultBucket = defaultDeps,
            hierarchyBuckets = hierarchyBuckets,
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
        val descendantLeafNames = graph.descendantLeafNames(bucketName, leafNames)
        val descendantLeafClosures = descendantLeafNames.mapNotNull { leafName -> leafClosures[leafName] }
        val explicitDeps = hierarchyBucketClosures[bucketName]
            .orEmpty()
            .onlyDependenciesPresentIn(descendantLeafClosures)
            .withResolvedLeafMetadata(descendantLeafClosures)
        val inferredDeps = if (descendantLeafNames.size < 2) {
            emptyMap()
        } else {
            intersectByBucketOwner(
                descendantLeafClosures
            )
        }
        return explicitDeps.withInferredClosure(inferredDeps)
    }

    private fun Map<String, ResolvedDependency>.withResolvedLeafMetadata(
        leafClosures: Collection<Map<String, ResolvedDependency>>
    ): Map<String, ResolvedDependency> {
        if (isEmpty() || leafClosures.isEmpty()) return this
        return mapValues { (shortId, explicitDependency) ->
            val resolvedDependency = leafClosures
                .asSequence()
                .mapNotNull { leafClosure -> leafClosure[shortId] }
                .reduceOrNull(::mergeDependencyMetadataByMaxVersion)
            resolvedDependency?.merge(explicitDependency) ?: explicitDependency
        }.toSortedMap()
    }

    private fun Map<String, ResolvedDependency>.onlyDependenciesPresentIn(
        leafClosures: Collection<Map<String, ResolvedDependency>>
    ): Map<String, ResolvedDependency> {
        if (isEmpty() || leafClosures.isEmpty()) return this
        return filter { (shortId, _) ->
            leafClosures.any { leafClosure -> shortId in leafClosure }
        }
    }

    private fun Map<String, ResolvedDependency>.withInferredClosure(
        inferredDeps: Map<String, ResolvedDependency>
    ): Map<String, ResolvedDependency> {
        if (isEmpty()) return inferredDeps
        if (inferredDeps.isEmpty()) return this
        val merged = toMutableMap()
        inferredDeps.forEach { (shortId, inferredDependency) ->
            val explicitDependency = merged[shortId]
            merged[shortId] = if (explicitDependency == null) {
                inferredDependency
            } else {
                inferredDependency.merge(explicitDependency).copy(
                    requiresJetifier = inferredDependency.requiresJetifier ||
                        explicitDependency.requiresJetifier
                )
            }
        }
        return merged.toSortedMap()
    }
}

private class MainBucketGraph(
    variants: Collection<MainBucketVariant>,
    private val baseBucketName: String
) {
    private val leafVariants = variants.filter(MainBucketVariant::leaf)
    val leafVariantNames = leafVariants.map(MainBucketVariant::name).toSortedSet()
    val buildTypeNames = leafVariants.mapNotNull(MainBucketVariant::buildType).toSortedSet()
    val flavorNames = leafVariants.flatMap(MainBucketVariant::productFlavors).toSortedSet()
    val hierarchyBucketNames = (
        variants.filterNot(MainBucketVariant::leaf).map(MainBucketVariant::name) +
            buildTypeNames +
            flavorNames +
            baseBucketName
        )
        .toSortedSet()
    private val parentsByName = buildMap<String, Set<String>> {
        variants.forEach { variant ->
            put(variant.name, variant.extendsFrom)
        }
        variants.flatMap(MainBucketVariant::extendsFrom).forEach { parentName ->
            putIfAbsent(
                parentName,
                if (parentName == baseBucketName) emptySet() else setOf(baseBucketName)
            )
        }
        hierarchyBucketNames.forEach { bucketName ->
            putIfAbsent(
                bucketName,
                if (bucketName == baseBucketName) emptySet() else setOf(baseBucketName)
            )
        }
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

    fun coversSelectedLeaves(
        bucketName: String,
        leafNames: Collection<String>
    ): Boolean {
        if (leafNames.isEmpty()) return false
        return descendantLeafNames(bucketName, leafNames).toSet() == leafNames.toSet()
    }
}

internal fun DeclaredDependencyMetadata.mainBucketVariants(projectPath: String): List<MainBucketVariant> {
    val androidBuildVariants = projects[projectPath]
        ?.variants
        .orEmpty()
        .asSequence()
        .filter { variant -> variant.variantType == AndroidBuild }
        .toList()
    val declaredOwnerBucketNames = androidBuildVariants
        .asSequence()
        .flatMap { variant -> variant.declaredDependencyDeclarations.asSequence() }
        .map(DeclaredExternalDependency::bucketName)
        .filter { bucketName -> bucketName != DEFAULT_VARIANT }
        .toSortedSet()
    val declaredOwnersByName = declaredOwnerBucketNames
        .mapNotNull { bucketName ->
            androidBuildVariants.ownerVariantFor(
                projectPath = projectPath,
                bucketName = bucketName
            )
        }
        .associateBy(MainBucketVariant::name)

    return androidBuildVariants
        .asSequence()
        .map { variant ->
            val declaredOwnersForLeaf = if (variant.androidLeafVariant) {
                declaredOwnersByName
                    .keys
                    .filter { ownerName ->
                        ownerName != variant.name &&
                            androidBuildVariants.ownerAppliesToLeaf(
                                ownerName = ownerName,
                                leafVariant = variant
                            )
                    }
                    .toSet()
            } else {
                emptySet()
            }
            MainBucketVariant(
                name = variant.name,
                extendsFrom = variant.extendsFrom + declaredOwnersForLeaf,
                buildType = variant.buildType,
                productFlavors = variant.productFlavors,
                leaf = variant.androidLeafVariant,
                projectPath = projectPath
            )
        }
        .plus(declaredOwnersByName.values.asSequence())
        .sortedBy(MainBucketVariant::name)
        .toList()
}

private fun Collection<DeclaredVariantDependencyMetadata>.ownerVariantFor(
    projectPath: String,
    bucketName: String
): MainBucketVariant? {
    val matchingLeaf = firstOrNull { variant ->
        variant.androidLeafVariant && variant.name == bucketName
    }
    if (matchingLeaf != null) {
        return MainBucketVariant(
            name = matchingLeaf.name,
            extendsFrom = matchingLeaf.extendsFrom,
            buildType = matchingLeaf.buildType,
            productFlavors = matchingLeaf.productFlavors,
            leaf = true,
            projectPath = projectPath
        )
    }
    val matchingLeafCandidates = filter { variant ->
        variant.androidLeafVariant && ownerAppliesToLeaf(bucketName, variant)
    }
    if (matchingLeafCandidates.isEmpty()) return null

    val flavorNames = matchingLeafCandidates
        .flatMap(DeclaredVariantDependencyMetadata::productFlavors)
        .distinct()
        .sortedByDescending(String::length)
    val buildTypeNames = matchingLeafCandidates
        .mapNotNull(DeclaredVariantDependencyMetadata::buildType)
        .distinct()
        .sortedByDescending(String::length)
    val ownerFlavors = flavorNames
        .filter { flavorName -> bucketName.contains(flavorName, ignoreCase = true) }
        .sortedWith(compareBy { flavorName ->
            matchingLeafCandidates.first().productFlavors.indexOf(flavorName).takeIf { it >= 0 } ?: Int.MAX_VALUE
        })
    val ownerBuildType = buildTypeNames
        .firstOrNull { buildTypeName -> bucketName.endsWith(buildTypeName, ignoreCase = true) }
    return MainBucketVariant(
        name = bucketName,
        extendsFrom = (setOf(DEFAULT_VARIANT) + ownerFlavors + listOfNotNull(ownerBuildType)).toSortedSet(),
        buildType = ownerBuildType,
        productFlavors = ownerFlavors,
        leaf = false,
        projectPath = projectPath
    )
}

private fun Collection<DeclaredVariantDependencyMetadata>.ownerAppliesToLeaf(
    ownerName: String,
    leafVariant: DeclaredVariantDependencyMetadata
): Boolean {
    return candidateOwnerBucketNames(leafVariant).contains(ownerName)
}

private fun candidateOwnerBucketNames(
    leafVariant: DeclaredVariantDependencyMetadata
): Set<String> {
    if (!leafVariant.androidLeafVariant) return emptySet()
    val flavors = leafVariant.productFlavors
    val flavorCombinations = flavors.orderedCombinations()
    val buildType = leafVariant.buildType
    return buildSet {
        addAll(flavors)
        buildType?.let(::add)
        addAll(flavorCombinations)
        if (buildType != null) {
            addAll(flavorCombinations.map { flavorBucket -> flavorBucket + buildType.capitalize() })
            flavors.forEach { flavorName -> add(flavorName + buildType.capitalize()) }
        }
        add(leafVariant.name)
    }
}

private fun List<String>.orderedCombinations(): Set<String> {
    if (size < 2) return emptySet()
    val combinations = sortedSetOf<String>()

    fun visit(index: Int, selected: List<String>) {
        if (index == size) {
            if (selected.size >= 2) {
                combinations += selected.joinToString(separator = "") { part ->
                    if (selected.first() == part) part else part.capitalize()
                }
            }
            return
        }
        visit(index + 1, selected)
        visit(index + 1, selected + this[index])
    }

    visit(index = 0, selected = emptyList())
    return combinations
}

internal fun DeclaredDependencyMetadata.mainBucketVariantsByProject(): List<MainBucketVariant> {
    return projects
        .keys
        .sorted()
        .flatMap { projectPath -> mainBucketVariants(projectPath) }
}

internal fun DeclaredDependencyMetadata.testBucketVariantsByProject(
    variantType: VariantType,
    baseBucketName: String
): List<MainBucketVariant> {
    return projects
        .keys
        .sorted()
        .flatMap { projectPath ->
            projects[projectPath]
                ?.variants
                .orEmpty()
                .asSequence()
                .filter { variant -> variant.variantType == variantType }
                .map { variant ->
                    MainBucketVariant(
                        name = variant.name,
                        extendsFrom = variant.testBucketExtendsFrom(
                            variantType = variantType,
                            baseBucketName = baseBucketName
                        ),
                        buildType = null,
                        productFlavors = emptyList(),
                        leaf = variant.androidLeafVariant,
                        projectPath = projectPath
                    )
                }
                .toList()
        }
        .sortedWith(compareBy(MainBucketVariant::projectPath).thenBy(MainBucketVariant::name))
}

private fun DeclaredVariantDependencyMetadata.testBucketExtendsFrom(
    variantType: VariantType,
    baseBucketName: String
): Set<String> {
    if (name == baseBucketName) return emptySet()
    return (extendsFrom.filter { parentName ->
        parentName == baseBucketName || parentName.endsWith(variantType.testSuffix)
    } + baseBucketName).toSortedSet()
}

internal fun Map<String, ResolvedDependency>.asCoveredBy(bucketName: String): List<CoveredDependency> {
    return values.map { dependency -> CoveredDependency(bucketName, dependency) }
}
