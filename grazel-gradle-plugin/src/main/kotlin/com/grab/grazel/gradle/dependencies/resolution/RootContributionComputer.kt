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

package com.grab.grazel.gradle.dependencies.resolution

import com.grab.grazel.gradle.dependencies.AggregatedDependencyRoot
import com.grab.grazel.gradle.dependencies.AggregatedDependencyRootKind
import com.grab.grazel.gradle.dependencies.AggregatedDependencyRootMetadata
import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.DeclaredVariantDependencyMetadata
import com.grab.grazel.gradle.dependencies.ProjectExcludeRules
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.Test

/**
 * Which bucket a [RootContributionComputer.compute] result should be folded into, alongside the
 * synthetic bucket name to fold it under. Pairs of these make up [RootContribution.routing].
 */
internal data class BucketRouting(val target: BucketTarget, val bucketName: String)

/**
 * Everything the spine (`AggregatedDependencyResolver.ResolutionSession.resolve`) needs to fold a
 * single visited [AggregatedDependencyRoot] into shared state, replacing the three parallel
 * `when(metadata.kind)` blocks `collectRootClosures` used to run inline.
 *
 * - [scope] is non-null only for MAIN_HIERARCHY/MAIN_LEAF roots. By the time [compute] returns it,
 *   the scope has *already* been folded into the [MainReachabilityTracker] passed to the computer
 *   (mirroring `collectRootClosures`'s literal seed-before-resolve order, since
 *   `resolveRootToDependencyMap`'s own reachability-consulting callback for later roots must see
 *   this root's contribution first) — it is exposed here purely so callers/tests can observe what
 *   was recorded, not so the spine can record it again.
 * - [outcome] is always populated ([RootVisitOutcome.dependencies] backs [lintClosure] for LINT
 *   roots). The spine should fold its reachability delta via [MainReachabilityTracker.recordReachable]
 *   for every kind *except* LINT (signalled here by [lintClosure] being non-null), matching
 *   `collectRootClosures`, which never folded reachability for LINT roots.
 * - [routing] lists every `(BucketTarget, bucketName)` pair the resolved closure should be folded
 *   into via [DependencyBucketAccumulator.fold]. HIERARCHY/TEST_HIERARCHY targets must be folded
 *   with `keepEmpty = false`, matching `collectRootClosures`.
 * - [lintClosure] is non-null only for LINT roots, for folding via [DependencyBucketAccumulator.foldLint].
 * - [seedsBinaryRoot] is true for MAIN_HIERARCHY/MAIN_LEAF roots (unconditionally, even when the
 *   leaf/bucket name is missing and [routing] ends up empty), matching `collectRootClosures` setting
 *   `sawBinaryRoot = true` before any leaf-name null check.
 */
internal data class RootContribution(
    val scope: MainProjectEdgeScope?,
    val outcome: RootVisitOutcome,
    val routing: List<BucketRouting>,
    val lintClosure: Map<String, ResolvedDependency>?,
    val seedsBinaryRoot: Boolean,
)

private typealias ResolveRoot = (
    aggregatedRoot: AggregatedDependencyRoot,
    excludeRulesByProjectPath: Map<String, ProjectExcludeRules>,
    projectEdgeExcludedShortIdsByTargetProject: Map<String, Set<String>>,
    reachableBucketNamesForProject: ((String, String?) -> Set<String>)?
) -> RootVisitOutcome

/**
 * Collapses `collectRootClosures`'s three parallel `when(metadata.kind)` blocks (exclude-rule
 * selection, closure resolution + reachability seeding, and closure-to-bucket routing) into a
 * single per-kind computation, so `ResolutionSession.resolve()` can be reduced to a thin spine
 * that just folds the [RootContribution] this returns. Behaviour is verbatim from
 * `AggregatedDependencyResolver.ResolutionSession.collectRootClosures`.
 *
 * @param tracker Shared [MainReachabilityTracker] instance for the in-flight [resolve][
 *   com.grab.grazel.gradle.dependencies.AggregatedDependencyResolver.resolve] call. MAIN_HIERARCHY/
 *   MAIN_LEAF roots seed it (via [MainReachabilityTracker.computeScope] +
 *   [MainReachabilityTracker.recordMainRoot] + [MainReachabilityTracker.addReachableMainBuckets])
 *   before their own closure is resolved, and every kind's closure resolution consults it via
 *   [MainReachabilityTracker.selectedMainVariantHierarchyNames].
 * @param declaredDependencyMetadata Used to look up per-project variants (for
 *   [variantNamesForLeafTest] scoping) and to collect exclude rules for MAIN/TEST_HIERARCHY kinds.
 * @param resolveRoot Reference to `AggregatedDependencyResolver.resolveRootToDependencyMap`,
 *   threaded in so this class stays unit-testable with a stub that returns a fixed
 *   [RootVisitOutcome] instead of walking a real resolved component graph.
 */
internal class RootContributionComputer(
    private val tracker: MainReachabilityTracker,
    private val declaredDependencyMetadata: DeclaredDependencyMetadata,
    private val resolveRoot: ResolveRoot,
) {
    private val projectMetadataByPath = declaredDependencyMetadata.projects

    private fun variantsFor(projectPath: String): List<DeclaredVariantDependencyMetadata> =
        projectMetadataByPath[projectPath]?.variants.orEmpty()

    private fun variantHierarchyNames(metadata: AggregatedDependencyRootMetadata): Set<String> {
        return metadata.variantNames.ifEmpty {
            listOfNotNull(metadata.leafName, metadata.bucketName).toSet()
        }
    }

    private fun targetBucketNames(metadata: AggregatedDependencyRootMetadata): Set<String> {
        return metadata.targetBuckets.ifEmpty {
            listOfNotNull(metadata.bucketName).toSet()
        }
    }

    private fun excludeRulesFor(
        metadata: AggregatedDependencyRootMetadata,
        variantType: VariantType
    ): Map<String, ProjectExcludeRules> {
        return declaredDependencyMetadata.collectExcludeRulesByProjectPath(
            variantTypes = setOf(variantType),
            variantNames = variantHierarchyNames(metadata)
        )
    }

    fun compute(root: AggregatedDependencyRoot): RootContribution {
        val metadata = root.metadata
        return when (metadata.kind) {
            AggregatedDependencyRootKind.MAIN_HIERARCHY,
            AggregatedDependencyRootKind.MAIN_LEAF -> computeMain(root, metadata)

            AggregatedDependencyRootKind.TEST_HIERARCHY -> computeTestHierarchy(root, metadata)
            AggregatedDependencyRootKind.UNIT_TEST -> computeLeafTest(root, metadata, Test, BucketTarget.LEAF_UNIT_TEST)
            AggregatedDependencyRootKind.ANDROID_TEST -> computeLeafTest(root, metadata, AndroidTest, BucketTarget.LEAF_ANDROID_TEST)
            AggregatedDependencyRootKind.LINT -> computeLint(root)
        }
    }

    /**
     * MAIN_HIERARCHY/MAIN_LEAF: seeds reachability for [metadata.projectPath] *before* resolving
     * its own closure (verbatim order from `collectRootClosures`), then routes the resolved closure
     * per [AggregatedDependencyRootKind]. `sawBinaryRoot` is set unconditionally, even if
     * MAIN_LEAF's leaf/bucket name is missing and routing ends up empty.
     */
    private fun computeMain(
        root: AggregatedDependencyRoot,
        metadata: AggregatedDependencyRootMetadata
    ): RootContribution {
        val hierarchyNames = variantHierarchyNames(metadata)
        val scope = tracker.computeScope(
            projectPath = metadata.projectPath,
            variantNames = hierarchyNames,
            selectedOnly = true
        )
        tracker.recordMainRoot(metadata, scope)
        tracker.addReachableMainBuckets(projectPath = metadata.projectPath, bucketNames = hierarchyNames)

        val outcome = resolveRoot(
            root,
            excludeRulesFor(metadata, AndroidBuild),
            scope.excludedShortIdsByTargetProject,
            tracker::selectedMainVariantHierarchyNames
        )

        val routing = when (metadata.kind) {
            AggregatedDependencyRootKind.MAIN_HIERARCHY -> targetBucketNames(metadata).map { bucketName ->
                when (bucketName) {
                    TEST_VARIANT, ANDROID_TEST_VARIANT -> BucketRouting(BucketTarget.TEST_HIERARCHY, bucketName)
                    else -> BucketRouting(BucketTarget.HIERARCHY, bucketName)
                }
            }

            else -> { // MAIN_LEAF
                val leafName = metadata.leafName ?: metadata.bucketName
                if (leafName == null) {
                    emptyList()
                } else {
                    targetBucketNames(metadata).map { bucketName ->
                        when (bucketName) {
                            DEFAULT_VARIANT -> BucketRouting(BucketTarget.HIERARCHY, DEFAULT_VARIANT)
                            TEST_VARIANT -> BucketRouting(BucketTarget.LEAF_UNIT_TEST, leafName)
                            ANDROID_TEST_VARIANT -> BucketRouting(BucketTarget.LEAF_ANDROID_TEST, leafName)
                            else -> BucketRouting(BucketTarget.LEAF, bucketName)
                        }
                    }
                }
            }
        }

        return RootContribution(
            scope = scope,
            outcome = outcome,
            routing = routing,
            lintClosure = null,
            seedsBinaryRoot = true
        )
    }

    private fun computeTestHierarchy(
        root: AggregatedDependencyRoot,
        metadata: AggregatedDependencyRootMetadata
    ): RootContribution {
        val variantType = metadata.variantType ?: when (metadata.bucketName) {
            ANDROID_TEST_VARIANT -> AndroidTest
            else -> Test
        }
        val outcome = resolveRoot(
            root,
            excludeRulesFor(metadata, variantType),
            emptyMap(),
            tracker::selectedMainVariantHierarchyNames
        )
        val routing = targetBucketNames(metadata).map { bucketName ->
            BucketRouting(BucketTarget.TEST_HIERARCHY, bucketName)
        }
        return RootContribution(
            scope = null,
            outcome = outcome,
            routing = routing,
            lintClosure = null,
            seedsBinaryRoot = false
        )
    }

    /**
     * UNIT_TEST/ANDROID_TEST: shared shape (only the [testType]/[target] differ), scoping exclude
     * rules via [variantNamesForLeafTest] and folding the whole closure into a single leaf bucket
     * named after [AggregatedDependencyRootMetadata.leafName] (or [AggregatedDependencyRootMetadata.bucketName]).
     */
    private fun computeLeafTest(
        root: AggregatedDependencyRoot,
        metadata: AggregatedDependencyRootMetadata,
        testType: VariantType,
        target: BucketTarget
    ): RootContribution {
        val leafHierarchyNames = variantHierarchyNames(metadata)
        val outcome = resolveRoot(
            root,
            declaredDependencyMetadata.collectExcludeRulesByProjectPath(
                variantTypes = setOf(testType),
                variantNames = variantNamesForLeafTest(
                    variants = variantsFor(metadata.projectPath),
                    leafHierarchyNames = leafHierarchyNames,
                    testType = testType
                )
            ),
            emptyMap(),
            tracker::selectedMainVariantHierarchyNames
        )
        val leafName = metadata.leafName ?: metadata.bucketName
        val routing = if (leafName == null) {
            emptyList()
        } else {
            listOf(BucketRouting(target, leafName))
        }
        return RootContribution(
            scope = null,
            outcome = outcome,
            routing = routing,
            lintClosure = null,
            seedsBinaryRoot = false
        )
    }

    private fun computeLint(root: AggregatedDependencyRoot): RootContribution {
        val outcome = resolveRoot(root, emptyMap(), emptyMap(), null)
        return RootContribution(
            scope = null,
            outcome = outcome,
            routing = emptyList(),
            lintClosure = outcome.dependencies,
            seedsBinaryRoot = false
        )
    }

    /**
     * Widens [leafHierarchyNames] (a single leaf variant plus its `extendsFrom` chain) with the
     * names of any [testType] test variant that extends — directly or transitively via its own
     * `extendsFrom` — one of those leaf names. Both the test variant's own name and everything it
     * extends from are unioned in, not just the matched test variant, because exclude-rule lookups
     * ([ProjectExcludeRules.rulesFor]) need the full hierarchy to find rules attached anywhere along
     * the chain. Used only to scope exclude-rule collection for UNIT_TEST/ANDROID_TEST roots — the
     * leaf variant itself may have no test variant pointing at it, in which case this is a no-op.
     * Relocated verbatim from `AggregatedDependencyResolver`.
     */
    private fun variantNamesForLeafTest(
        variants: Collection<DeclaredVariantDependencyMetadata>,
        leafHierarchyNames: Set<String>,
        testType: VariantType
    ): Set<String> {
        val relevantTestVariantNames = variants
            .asSequence()
            .filter { variant -> variant.variantType == testType }
            .filter { variant ->
                variant.name in leafHierarchyNames ||
                    variant.extendsFrom.any { it in leafHierarchyNames }
            }
            .flatMap { variant -> (setOf(variant.name) + variant.extendsFrom).asSequence() }
            .toSet()
        return leafHierarchyNames + relevantTestVariantNames
    }
}
