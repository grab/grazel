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
import com.grab.grazel.gradle.variant.BucketHierarchyEntry
import com.grab.grazel.gradle.variant.BucketHierarchyGraph
import com.grab.grazel.gradle.variant.BucketHierarchyNode
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild

internal data class BucketPlacementVariantInput(
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
    val leafBuckets: Map<String, Map<String, ResolvedDependency>>,
    val bucketAncestors: Map<String, Set<String>>,
    val leafAncestors: Map<String, Set<String>>,
    val bucketDescendantLeaves: Map<String, Set<String>>,
    val variantTypesByBucketName: Map<String, VariantType>
) {
    fun coveredDependencies(): List<CoveredDependency> {
        return buildList {
            addAll(coveredDependenciesForBucket(defaultBucket, baseBucketName))
            hierarchyBuckets.forEach { (bucketName, deps) ->
                addAll(coveredDependenciesForBucket(deps, bucketName))
            }
            leafBuckets.forEach { (bucketName, deps) ->
                addAll(coveredDependenciesForBucket(deps, bucketName))
            }
        }
    }
}

internal class DependencyBucketPlacementEngine {
    fun planByProject(
        variants: Collection<BucketPlacementVariantInput>,
        hierarchyBucketClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        leafClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        baseBucketName: String = DEFAULT_VARIANT
    ): Map<String, DependencyBucketPlacementPlan> {
        val variantsByProject = variants
            .filter { variant -> variant.projectPath.isNotBlank() }
            .groupBy(BucketPlacementVariantInput::projectPath)
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
        variants: Collection<BucketPlacementVariantInput>,
        hierarchyBucketClosures: Map<String, Map<String, ResolvedDependency>>,
        leafClosures: Map<String, Map<String, ResolvedDependency>>,
        baseBucketName: String = DEFAULT_VARIANT
    ): DependencyBucketPlacementPlan {
        val graph = BucketPlacementGraph(variants, baseBucketName)
        val leafNames = leafClosures.keys.toSortedSet()
        val selectedDescendantLeafNamesByBucket = graph.bucketNames.associateWith { bucketName ->
            graph.descendantLeafNames(bucketName, leafNames)
        }.toSortedMap()
        fun selectedDescendantLeafNames(bucketName: String): Set<String> {
            return selectedDescendantLeafNamesByBucket[bucketName].orEmpty()
        }

        fun coversSelectedLeaves(bucketName: String): Boolean {
            return leafNames.isNotEmpty() && selectedDescendantLeafNames(bucketName) == leafNames
        }

        fun coversDescendantLeaves(coveringBucketName: String, coveredBucketName: String): Boolean {
            val coveredLeaves = selectedDescendantLeafNames(coveredBucketName)
            if (coveredLeaves.isEmpty()) return false
            return selectedDescendantLeafNames(coveringBucketName).containsAll(coveredLeaves)
        }

        val selectedLeafClosures = leafNames.mapNotNull { leafName -> leafClosures[leafName] }
        val hierarchyDefaultDeps = withResolvedLeafMetadata(
            dependencies = onlyDependenciesPresentIn(
                dependencies = hierarchyBucketClosures[baseBucketName].orEmpty(),
                leafClosures = selectedLeafClosures
            ),
            leafClosures = selectedLeafClosures
        )
        val nonDefaultHierarchyDeps = hierarchyBucketClosures
            .filterKeys { bucketName -> bucketName != baseBucketName }
            .values
            .flatMap { deps -> deps.values }
        val inferredDefaultDeps = if (leafNames.size > 1) {
            intersectByBucketOwner(selectedLeafClosures)
        } else {
            emptyMap()
        }
        val candidateDepsByBucketName = linkedMapOf<String, Map<String, ResolvedDependency>>()
        fun candidateDepsForBucket(bucketName: String): Map<String, ResolvedDependency> {
            return candidateDepsByBucketName.getOrPut(bucketName) {
                candidateDepsFor(
                    bucketName = bucketName,
                    descendantLeafNames = selectedDescendantLeafNames(bucketName),
                    leafClosures = leafClosures,
                    hierarchyBucketClosures = hierarchyBucketClosures
                )
            }
        }
        val fullCoverageHierarchyDeps = graph.hierarchyBucketNames
            .filter { bucketName ->
                bucketName != baseBucketName && coversSelectedLeaves(bucketName)
            }
            .flatMap { bucketName -> candidateDepsForBucket(bucketName).values }
        val defaultDeps = (
            inferredDefaultDeps +
                hierarchyDefaultDeps
            )
            .let { dependencies ->
                withoutDependenciesOwnedByNonDefaultHierarchy(
                    dependenciesByShortId = dependencies,
                    hierarchyDefaultDeps = hierarchyDefaultDeps,
                    nonDefaultHierarchyDependencies = nonDefaultHierarchyDeps + fullCoverageHierarchyDeps
                )
            }

        val selectedHierarchyBuckets = linkedMapOf<String, Map<String, ResolvedDependency>>()
        val defaultCoveredDeps = coveredDependenciesForBucket(defaultDeps, baseBucketName)

        fun selectedCoveredDepsFor(bucketName: String): List<CoveredDependency> {
            return selectedHierarchyBuckets
                .filterKeys { selectedBucketName ->
                    graph.hasAncestor(bucketName, selectedBucketName) ||
                        graph.hasAncestor(selectedBucketName, bucketName) ||
                        coversDescendantLeaves(
                            coveringBucketName = selectedBucketName,
                            coveredBucketName = bucketName
                        )
                }
                .flatMap { (selectedBucketName, deps) ->
                    coveredDependenciesForBucket(deps, selectedBucketName)
                }
        }

        val explicitBucketNames = hierarchyBucketClosures
            .keys
            .filter { bucketName -> bucketName != baseBucketName }
            .sortedWith(compareByDescending<String> { bucketName -> graph.depthOf(bucketName) }.thenBy { it })

        fun selectHierarchyBucket(bucketName: String) {
            val deps = candidateDepsForBucket(bucketName)
                .let { dependencies ->
                    withoutDependenciesCoveredBy(
                        dependenciesByShortId = dependencies,
                        coveredDependencies = defaultCoveredDeps + selectedCoveredDepsFor(bucketName)
                    )
                }
            if (deps.isNotEmpty()) {
                selectedHierarchyBuckets[bucketName] = deps
            }
        }

        explicitBucketNames.forEach(::selectHierarchyBucket)

        val inferredBucketNames = graph.hierarchyBucketNames
            .filter { bucketName -> bucketName != baseBucketName && bucketName !in hierarchyBucketClosures }
            .sortedWith(
                compareByDescending<String> { bucketName ->
                    selectedDescendantLeafNames(bucketName).size
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
        val leafAncestors = graph.leafVariantNames.associateWith { leafName ->
            graph.ancestorsOf(leafName)
        }.toSortedMap()
        val bucketAncestors = graph.bucketNames.associateWith { bucketName ->
            graph.ancestorsOf(bucketName)
        }.toSortedMap()
        val bucketDescendantLeaves = selectedDescendantLeafNamesByBucket
        val variantTypesByBucketName = graph.variantTypesByBucketName
        val outputLeafNames = (leafNames + selectedLeafBuckets.keys).toSortedSet()
        val leafBuckets = outputLeafNames
            .mapNotNull { leafName ->
                val ancestorCoveredDeps = leafAncestors[leafName]
                    .orEmpty()
                    .filter { parentName -> parentName != baseBucketName }
                    .flatMap { parentName ->
                        coveredDependenciesForBucket(
                            dependenciesByShortId = selectedHierarchyBuckets[parentName].orEmpty(),
                            bucketName = parentName
                        )
                    }
                val selectedLeafDeps = selectedLeafBuckets[leafName].orEmpty()
                val residualDeps = leafClosures[leafName]
                    .orEmpty()
                    .let { dependencies ->
                        withoutDependenciesCoveredBy(
                            dependenciesByShortId = dependencies,
                            coveredDependencies = defaultCoveredDeps +
                                ancestorCoveredDeps +
                                coveredDependenciesForBucket(selectedLeafDeps, leafName)
                        )
                    }
                val deps = residualDeps + selectedLeafDeps
                if (deps.isEmpty()) null else leafName to deps
            }
            .toMap()

        return DependencyBucketPlacementPlan(
            baseBucketName = baseBucketName,
            defaultBucket = defaultDeps,
            hierarchyBuckets = hierarchyBuckets,
            leafBuckets = leafBuckets,
            bucketAncestors = bucketAncestors,
            leafAncestors = leafAncestors,
            bucketDescendantLeaves = bucketDescendantLeaves,
            variantTypesByBucketName = variantTypesByBucketName
        )
    }

    private fun candidateDepsFor(
        bucketName: String,
        descendantLeafNames: Set<String>,
        leafClosures: Map<String, Map<String, ResolvedDependency>>,
        hierarchyBucketClosures: Map<String, Map<String, ResolvedDependency>>
    ): Map<String, ResolvedDependency> {
        if (leafClosures.isNotEmpty() && descendantLeafNames.isEmpty()) {
            return emptyMap()
        }
        val descendantLeafClosures = descendantLeafNames.mapNotNull { leafName -> leafClosures[leafName] }
        val explicitDeps = withResolvedLeafMetadata(
            dependencies = onlyDependenciesPresentIn(
                dependencies = hierarchyBucketClosures[bucketName].orEmpty(),
                leafClosures = descendantLeafClosures
            ),
            leafClosures = descendantLeafClosures
        )
        val inferredDeps = if (descendantLeafNames.size < 2) {
            emptyMap()
        } else {
            intersectByBucketOwner(
                descendantLeafClosures
            )
        }
        return withInferredClosure(
            explicitDependencies = explicitDeps,
            inferredDependencies = inferredDeps
        )
    }

    private fun withResolvedLeafMetadata(
        dependencies: Map<String, ResolvedDependency>,
        leafClosures: Collection<Map<String, ResolvedDependency>>
    ): Map<String, ResolvedDependency> {
        if (dependencies.isEmpty() || leafClosures.isEmpty()) return dependencies
        return dependencies.mapValues { (shortId, explicitDependency) ->
            val leafDependencies = leafClosures.asSequence()
                .mapNotNull { leafClosure -> leafClosure[shortId] }
            val resolvedDependency = if (explicitDependency.isDeclaredMetadata()) {
                leafDependencies.reduceOrNull(::mergeDependencyMetadataByMaxVersion)
            } else {
                val sameVersionLeafDependencies = leafDependencies
                    .filter { leafDependency -> leafDependency.version == explicitDependency.version }
                    .toList()
                val matchingOwnerDependency = sameVersionLeafDependencies
                    .firstOrNull { leafDependency ->
                        leafDependency.excludeRules == explicitDependency.excludeRules &&
                            leafDependency.requiresJetifier == explicitDependency.requiresJetifier &&
                            leafDependency.jetifierSource == explicitDependency.jetifierSource
                    }
                matchingOwnerDependency ?: sameVersionLeafDependencies
                    .reduceOrNull(::mergeDependencyMetadataByMaxVersion)
            }
            resolvedDependency
                ?.let { dependency -> mergeDependencyMetadataByMaxVersion(dependency, explicitDependency) }
                ?: explicitDependency
        }.toSortedMap()
    }

    private fun onlyDependenciesPresentIn(
        dependencies: Map<String, ResolvedDependency>,
        leafClosures: Collection<Map<String, ResolvedDependency>>
    ): Map<String, ResolvedDependency> {
        if (dependencies.isEmpty() || leafClosures.isEmpty()) return dependencies
        return dependencies.filterKeys { shortId -> leafClosures.any { leafClosure -> shortId in leafClosure } }
    }

    private fun withInferredClosure(
        explicitDependencies: Map<String, ResolvedDependency>,
        inferredDependencies: Map<String, ResolvedDependency>
    ): Map<String, ResolvedDependency> {
        if (explicitDependencies.isEmpty()) return inferredDependencies
        if (inferredDependencies.isEmpty()) return explicitDependencies
        val merged = explicitDependencies.toMutableMap()
        inferredDependencies.forEach { (shortId, inferredDependency) ->
            val explicitDependency = merged[shortId]
            merged[shortId] = if (explicitDependency == null) {
                inferredDependency
            } else {
                explicitDependency.withInferredClosureMetadata(inferredDependency)
            }
        }
        return merged.toSortedMap()
    }

    private fun ResolvedDependency.withInferredClosureMetadata(
        inferredDependency: ResolvedDependency
    ): ResolvedDependency {
        return copy(
            direct = direct || inferredDependency.direct,
            dependencies = if (version == inferredDependency.version) {
                (dependencies + inferredDependency.dependencies).toSortedSet()
            } else {
                dependencies
            },
            requiresJetifier = requiresJetifier || inferredDependency.requiresJetifier,
            jetifierSource = jetifierSource ?: inferredDependency.jetifierSource,
            overrideTarget = overrideTarget ?: inferredDependency.overrideTarget,
            processorClass = processorClass ?: inferredDependency.processorClass
        )
    }
}

private class BucketPlacementGraph(
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

internal fun DeclaredDependencyMetadata.mainBucketVariants(projectPath: String): List<BucketPlacementVariantInput> {
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
            ownerVariantFor(
                variants = androidBuildVariants,
                projectPath = projectPath,
                bucketName = bucketName
            )
        }
        .associateBy(BucketPlacementVariantInput::name)

    return androidBuildVariants
        .asSequence()
        .map { variant ->
            val declaredOwnersForLeaf = if (variant.androidLeafVariant) {
                declaredOwnersByName
                    .values
                    .filter { owner ->
                        owner.name != variant.name &&
                            owner.appliesTo(leafVariant = variant)
                    }
                    .map(BucketPlacementVariantInput::name)
                    .toSet()
            } else {
                emptySet()
            }
            BucketPlacementVariantInput(
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
        .sortedBy(BucketPlacementVariantInput::name)
        .toList()
}

private fun BucketPlacementVariantInput.appliesTo(
    leafVariant: DeclaredVariantDependencyMetadata
): Boolean {
    if (name in leafVariant.extendsFrom) return true
    val parentNames = extendsFrom - DEFAULT_VARIANT - name
    return parentNames.isNotEmpty() && parentNames.all { parentName -> parentName in leafVariant.extendsFrom }
}

private fun ownerVariantFor(
    variants: Collection<DeclaredVariantDependencyMetadata>,
    projectPath: String,
    bucketName: String
): BucketPlacementVariantInput? {
    val matchingLeaf = variants.firstOrNull { variant ->
        variant.androidLeafVariant && variant.name == bucketName
    }
    if (matchingLeaf != null) {
        return BucketPlacementVariantInput(
            name = matchingLeaf.name,
            extendsFrom = matchingLeaf.extendsFrom,
            buildType = matchingLeaf.buildType,
            productFlavors = matchingLeaf.productFlavors,
            leaf = true,
            projectPath = projectPath,
            variantType = matchingLeaf.variantType
        )
    }
    val matchingLeafCandidates = variants.filter { variant ->
        variant.androidLeafVariant && ownerAppliesToLeaf(ownerName = bucketName, leafVariant = variant)
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
    return BucketPlacementVariantInput(
        name = bucketName,
        extendsFrom = (setOf(DEFAULT_VARIANT) + ownerFlavors + listOfNotNull(ownerBuildType)).toSortedSet(),
        buildType = ownerBuildType,
        productFlavors = ownerFlavors,
        leaf = false,
        projectPath = projectPath,
        variantType = AndroidBuild
    )
}

private fun ownerAppliesToLeaf(
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
    val flavorCombinations = orderedCombinations(flavors)
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

private fun orderedCombinations(parts: List<String>): Set<String> {
    if (parts.size < 2) return emptySet()
    val combinations = sortedSetOf<String>()

    fun visit(index: Int, selected: List<String>) {
        if (index == parts.size) {
            if (selected.size >= 2) {
                combinations += selected.joinToString(separator = "") { part ->
                    if (selected.first() == part) part else part.bucketPartCapitalized()
                }
            }
            return
        }
        visit(index + 1, selected)
        visit(index + 1, selected + parts[index])
    }

    visit(index = 0, selected = emptyList())
    return combinations
}

internal fun DeclaredDependencyMetadata.mainBucketVariantsByProject(): List<BucketPlacementVariantInput> {
    return projects
        .keys
        .sorted()
        .flatMap { projectPath -> mainBucketVariants(projectPath) }
}

internal fun DeclaredDependencyMetadata.testBucketVariantsByProject(
    variantType: VariantType,
    baseBucketName: String
): List<BucketPlacementVariantInput> {
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
                    BucketPlacementVariantInput(
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
        .sortedWith(compareBy(BucketPlacementVariantInput::projectPath).thenBy(BucketPlacementVariantInput::name))
}

private fun DeclaredVariantDependencyMetadata.testBucketExtendsFrom(
    baseBucketName: String
): Set<String> {
    return (extendsFrom + baseBucketName)
        .filter { parentName -> parentName != name }
        .toSortedSet()
}
