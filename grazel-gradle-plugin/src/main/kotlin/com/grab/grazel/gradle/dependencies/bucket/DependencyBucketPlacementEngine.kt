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

import com.grab.grazel.gradle.dependencies.ProjectDependencyBucket
import com.grab.grazel.gradle.dependencies.isDeclaredMetadata
import com.grab.grazel.gradle.dependencies.mergeDependencyMetadataByMaxVersion
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
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
    fun coveredDependencies(): List<CoveredDependency> =
        allCoveredDependencies(baseBucketName, defaultBucket, hierarchyBuckets, leafBuckets)
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

    /**
     * The core ownership placement algorithm for a single project: decides which bucket (default,
     * hierarchy, or leaf) should own each resolved dependency, aiming to place every dependency as
     * high in the variant hierarchy as its actual usage supports so it's declared exactly once.
     *
     * The algorithm proceeds in dependency order - each stage's output feeds the next's coverage
     * checks, so a dependency already placed higher up is never re-declared lower down:
     *
     * 1. **Default bucket**: seeded from explicit `hierarchyBucketClosures[baseBucketName]` plus,
     *    when there's more than one leaf, dependencies inferred to be common to *every* leaf's
     *    closure ([intersectByBucketOwner]). [withoutDependenciesOwnedByNonDefaultHierarchy] then
     *    prevents a dependency from being promoted to default if some other, non-default hierarchy
     *    bucket already claims exclusive ownership of it (unless default independently agrees).
     * 2. **Hierarchy buckets**: explicitly-declared buckets are selected first, in descending
     *    ancestor-depth order (deepest/most-specific first) so a child bucket's dependencies are
     *    placed before a shared ancestor is considered - each selection subtracts what default and
     *    already-selected sibling/ancestor buckets ([selectedCoveredDepsFor]) cover. Buckets with no
     *    explicit closure are then inferred, ordered by how many leaves they cover (widest first)
     *    then by depth, so broader inferred buckets get first claim on shared dependencies.
     * 3. **Leaf buckets**: whatever remains after subtracting default and all selected ancestor
     *    hierarchy buckets is the leaf's own residual - the true leaf-specific dependencies.
     *
     * This ordering (deepest-explicit -> widest-inferred -> leaf-residual) is itself an invariant:
     * reordering it would change which bucket "wins" a shared dependency and could either
     * over-promote a leaf-only dependency to a shared ancestor or leave duplicate declarations
     * scattered across sibling buckets.
     */
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
                    Coverage.of(defaultCoveredDeps + selectedCoveredDepsFor(bucketName))
                        .subtract(dependencies)
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
                        Coverage.of(
                            defaultCoveredDeps +
                                ancestorCoveredDeps +
                                coveredDependenciesForBucket(selectedLeafDeps, leafName)
                        ).subtract(dependencies)
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

    /**
     * A bucket's candidate dependencies are the union of what's explicitly declared for it
     * (resolved against its descendant leaves' metadata) and what's inferred purely from
     * intersecting its descendant leaves' closures. Short-circuits to empty when there are leaf
     * closures overall but this bucket covers none of the selected leaves - such a bucket cannot
     * legitimately own anything, since ownership is defined relative to which leaves see it. This
     * result feeds directly into [selectHierarchyBucket]'s covered-dependency subtraction, so an
     * empty/wrong candidate set here silently drops or misplaces dependencies downstream.
     */
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

    /**
     * Reconciles an explicitly-declared dependency's metadata against the matching entries in its
     * descendant leaf closures, since the explicit declaration alone may be missing detail (e.g.
     * exclude rules) that only the resolved leaf closures actually carry. A declared-metadata
     * placeholder ([ResolvedDependency.isDeclaredMetadata]) has no real resolution to disambiguate
     * with, so all matching leaf entries are simply merged together. For a real dependency, leaf
     * candidates are first narrowed to the same version, then preferred by an owner matching the
     * exact same exclude rules/jetifier requirement/source (the leaf that most likely represents
     * the "true" resolution) before falling back to merging all same-version candidates - this
     * multi-tier matching avoids blindly merging metadata from a leaf that resolved a
     * same-version-but-differently-configured (e.g. different excludes) copy of the dependency.
     */
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

    /**
     * The seam between explicit (user/build-declared) and inferred (leaf-intersection) ownership
     * for a bucket: explicit entries always win as the base, but when an inferred entry exists for
     * the same shortId, its extra information is folded in via
     * [ResolvedDependency.withInferredClosureMetadata] rather than discarded - an explicit
     * declaration and an intersection-derived observation of the same dependency are both partially
     * true and need to be reconciled, not one replacing the other outright.
     */
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

    /**
     * Field-by-field merge rule for what survives when an explicit (`this`) and inferred
     * ([inferredDependency]) view of the same dependency disagree. Each field's merge reflects why
     * the two views can differ in the first place:
     * - [direct] and [requiresJetifier] are OR'd - either view observing it as a root dependency, or
     *   as requiring jetifier, makes it so.
     * - [dependencies] (the transitive closure) is only unioned when both views agree on
     *   [version] - closures for different versions of a dependency are not interchangeable, so
     *   merging them would fabricate a closure that never actually existed for the explicit version.
     * - The remaining nullable metadata fields fall back to the inferred value only when the
     *   explicit view didn't record one, since an explicit declaration's own value (when present)
     *   is authoritative.
     */
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
