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
import com.grab.grazel.gradle.variant.BucketHierarchyEntry
import com.grab.grazel.gradle.variant.BucketHierarchyGraph
import com.grab.grazel.gradle.variant.BucketHierarchyNode
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild

internal data class DependencyBucketVariant(
    val name: String,
    val extendsFrom: Set<String>,
    val buildType: String?,
    val productFlavors: List<String>,
    val leaf: Boolean,
    val projectPath: String = "",
    val variantType: VariantType = AndroidBuild
)

internal data class DependencyBucketPlacementPlan(
    val baseBucketName: String = DEFAULT_VARIANT,
    val defaultBucket: Map<String, ResolvedDependency>,
    val hierarchyBuckets: Map<String, Map<String, ResolvedDependency>>,
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

internal class DependencyBucketPlacementEngine {
    fun planByProject(
        variants: Collection<DependencyBucketVariant>,
        hierarchyBucketClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        leafClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        baseBucketName: String = DEFAULT_VARIANT
    ): Map<String, DependencyBucketPlacementPlan> {
        val variantsByProject = variants
            .filter { variant -> variant.projectPath.isNotBlank() }
            .groupBy(DependencyBucketVariant::projectPath)
        val hierarchyBucketClosuresByProject = hierarchyBucketClosures
            .entries
            .groupBy { (bucket, _) -> bucket.projectPath }
        val leafClosuresByProject = leafClosures
            .entries
            .groupBy { (bucket, _) -> bucket.projectPath }
        val projectPaths = (
            variantsByProject.keys +
                hierarchyBucketClosuresByProject.keys +
                leafClosuresByProject.keys
            )
            .filter { projectPath -> projectPath.isNotBlank() }
            .toSortedSet()

        return projectPaths.associateWith { projectPath ->
            plan(
                variants = variantsByProject[projectPath].orEmpty(),
                hierarchyBucketClosures = hierarchyBucketClosuresByProject[projectPath]
                    .orEmpty()
                    .associate { (bucket, dependencies) -> bucket.bucketName to dependencies },
                leafClosures = leafClosuresByProject[projectPath]
                    .orEmpty()
                    .associate { (bucket, dependencies) -> bucket.bucketName to dependencies },
                baseBucketName = baseBucketName
            )
        }
    }

    fun plan(
        variants: Collection<DependencyBucketVariant>,
        hierarchyBucketClosures: Map<String, Map<String, ResolvedDependency>>,
        leafClosures: Map<String, Map<String, ResolvedDependency>>,
        baseBucketName: String = DEFAULT_VARIANT
    ): DependencyBucketPlacementPlan {
        val graph = BucketPlacementGraph(variants, baseBucketName)
        val leafNames = leafClosures.keys.sorted()
        val selectedLeafClosures = leafNames.mapNotNull { leafName -> leafClosures[leafName] }
        val hierarchyDefaultDeps = hierarchyBucketClosures[baseBucketName]
            .orEmpty()
            .onlyDependenciesPresentIn(selectedLeafClosures)
            .withResolvedLeafMetadata(selectedLeafClosures)
        val nonDefaultHierarchyDeps = hierarchyBucketClosures
            .filterKeys { bucketName -> bucketName != baseBucketName }
            .values
            .flatMap { deps -> deps.values }
        val inferredDefaultDeps = if (leafNames.size > 1) {
            intersectByBucketOwner(selectedLeafClosures)
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

        fun selectHierarchyBucket(bucketName: String) {
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

        explicitBucketNames.forEach(::selectHierarchyBucket)

        val inferredBucketNames = graph.hierarchyBucketNames
            .filter { bucketName -> bucketName != baseBucketName && bucketName !in hierarchyBucketClosures }
            .sortedWith(
                compareByDescending<String> { bucketName ->
                    graph.descendantLeafNames(bucketName, leafNames).size
                }
                    .thenByDescending { bucketName -> graph.depthOf(bucketName) }
                    .thenBy { bucketName -> bucketName }
            )

        inferredBucketNames.forEach(::selectHierarchyBucket)

        val hierarchyBuckets = selectedHierarchyBuckets
            .filterKeys { bucketName -> bucketName !in graph.leafVariantNames }
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

        return DependencyBucketPlacementPlan(
            baseBucketName = baseBucketName,
            defaultBucket = defaultDeps,
            hierarchyBuckets = hierarchyBuckets,
            leafBuckets = leafBuckets
        )
    }

    private fun candidateDepsFor(
        bucketName: String,
        graph: BucketPlacementGraph,
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
            val leafDependencies = leafClosures
                .asSequence()
                .mapNotNull { leafClosure -> leafClosure[shortId] }
                .toList()
            val sameVersionLeafDependencies = leafDependencies
                .filter { leafDependency -> leafDependency.version == explicitDependency.version }
            val matchingOwnerDependency = sameVersionLeafDependencies
                .firstOrNull { leafDependency ->
                    leafDependency.excludeRules == explicitDependency.excludeRules &&
                        leafDependency.requiresJetifier == explicitDependency.requiresJetifier &&
                        leafDependency.jetifierSource == explicitDependency.jetifierSource
                }
            val resolvedDependency = matchingOwnerDependency ?: sameVersionLeafDependencies
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

private class BucketPlacementGraph(
    variants: Collection<DependencyBucketVariant>,
    private val baseBucketName: String
) {
    private val leafVariants = variants.filter(DependencyBucketVariant::leaf)
    val leafVariantNames = leafVariants.map(DependencyBucketVariant::name).toSortedSet()
    private val buildTypeNames = leafVariants.mapNotNull(DependencyBucketVariant::buildType).toSortedSet()
    private val flavorNames = leafVariants.flatMap(DependencyBucketVariant::productFlavors).toSortedSet()
    private val projectPath = variants.firstOrNull()?.projectPath.orEmpty()
    private val defaultVariantType = variants.firstOrNull()?.variantType ?: AndroidBuild
    val hierarchyBucketNames = (
        variants.filterNot(DependencyBucketVariant::leaf).map(DependencyBucketVariant::name) +
            buildTypeNames +
            flavorNames +
            baseBucketName
        )
        .toSortedSet()
    private val nodeByName: Map<String, BucketHierarchyNode>
    private val graph: BucketHierarchyGraph

    init {
        val variantEntries = variants.map { variant ->
            BucketHierarchyEntry(
                node = variant.node(),
                extendsFrom = variant.extendsFrom,
                leaf = variant.leaf
            )
        }
        val variantNames = variants.map(DependencyBucketVariant::name).toSet()
        val syntheticNames = (
            variants.flatMap(DependencyBucketVariant::extendsFrom) +
                hierarchyBucketNames
            )
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
        return graph.hasAncestor(nodeFor(name), nodeFor(ancestor))
    }

    fun ancestorsOf(name: String): Set<String> {
        return graph.ancestorsOf(nodeFor(name)).mapTo(sortedSetOf(), BucketHierarchyNode::name)
    }

    fun depthOf(name: String): Int {
        return graph.depthOf(nodeFor(name))
    }

    fun descendantLeafNames(bucketName: String, leafNames: Collection<String>): List<String> {
        val selectedLeaves = leafNames.map(::nodeFor).toSet()
        return graph.leafDescendantsOf(nodeFor(bucketName))
            .filter { leaf -> leaf in selectedLeaves }
            .map(BucketHierarchyNode::name)
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

    private fun DependencyBucketVariant.node(): BucketHierarchyNode {
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

internal fun DeclaredDependencyMetadata.mainBucketVariants(projectPath: String): List<DependencyBucketVariant> {
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
        .associateBy(DependencyBucketVariant::name)

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
            DependencyBucketVariant(
                name = variant.name,
                extendsFrom = variant.extendsFrom + declaredOwnersForLeaf,
                buildType = variant.buildType,
                productFlavors = variant.productFlavors,
                leaf = variant.androidLeafVariant,
                projectPath = projectPath,
                variantType = variant.variantType
            )
        }
        .plus(declaredOwnersByName.values.asSequence())
        .sortedBy(DependencyBucketVariant::name)
        .toList()
}

private fun Collection<DeclaredVariantDependencyMetadata>.ownerVariantFor(
    projectPath: String,
    bucketName: String
): DependencyBucketVariant? {
    val matchingLeaf = firstOrNull { variant ->
        variant.androidLeafVariant && variant.name == bucketName
    }
    if (matchingLeaf != null) {
        return DependencyBucketVariant(
            name = matchingLeaf.name,
            extendsFrom = matchingLeaf.extendsFrom,
            buildType = matchingLeaf.buildType,
            productFlavors = matchingLeaf.productFlavors,
            leaf = true,
            projectPath = projectPath,
            variantType = matchingLeaf.variantType
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
    return DependencyBucketVariant(
        name = bucketName,
        extendsFrom = (setOf(DEFAULT_VARIANT) + ownerFlavors + listOfNotNull(ownerBuildType)).toSortedSet(),
        buildType = ownerBuildType,
        productFlavors = ownerFlavors,
        leaf = false,
        projectPath = projectPath,
        variantType = AndroidBuild
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
            addAll(flavorCombinations.map { flavorBucket -> flavorBucket + buildType.bucketPartCapitalized() })
            flavors.forEach { flavorName -> add(flavorName + buildType.bucketPartCapitalized()) }
        }
        add(leafVariant.name)
    }
}

private fun String.bucketPartCapitalized(): String {
    return replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase() else char.toString()
    }
}

private fun List<String>.orderedCombinations(): Set<String> {
    if (size < 2) return emptySet()
    val combinations = sortedSetOf<String>()

    fun visit(index: Int, selected: List<String>) {
        if (index == size) {
            if (selected.size >= 2) {
                combinations += selected.joinToString(separator = "") { part ->
                    if (selected.first() == part) part else part.bucketPartCapitalized()
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

internal fun DeclaredDependencyMetadata.mainBucketVariantsByProject(): List<DependencyBucketVariant> {
    return projects
        .keys
        .sorted()
        .flatMap { projectPath -> mainBucketVariants(projectPath) }
}

internal fun DeclaredDependencyMetadata.testBucketVariantsByProject(
    variantType: VariantType,
    baseBucketName: String
): List<DependencyBucketVariant> {
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
                    DependencyBucketVariant(
                        name = variant.name,
                        extendsFrom = variant.testBucketExtendsFrom(
                            baseBucketName = baseBucketName
                        ),
                        buildType = null,
                        productFlavors = emptyList(),
                        leaf = variant.androidLeafVariant,
                        projectPath = projectPath,
                        variantType = variant.variantType
                    )
                }
                .toList()
        }
        .sortedWith(compareBy(DependencyBucketVariant::projectPath).thenBy(DependencyBucketVariant::name))
}

private fun DeclaredVariantDependencyMetadata.testBucketExtendsFrom(
    baseBucketName: String
): Set<String> {
    if (name == baseBucketName) return emptySet()
    return (extendsFrom + baseBucketName).toSortedSet()
}

internal fun Map<String, ResolvedDependency>.asCoveredBy(bucketName: String): List<CoveredDependency> {
    return values.map { dependency -> CoveredDependency(bucketName, dependency) }
}
