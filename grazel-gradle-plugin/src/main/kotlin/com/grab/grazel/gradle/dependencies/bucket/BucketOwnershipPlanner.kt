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

/**
 * ## Bucket ownership planning — subsystem flow
 *
 * Turns each variant's flat resolved-dependency closure into a deduplicated *ownership plan*: every
 * dependency declared exactly once, as high in the variant hierarchy as its actual usage allows.
 * The output ([ResolveDependenciesResult] per bucket) is serialized and later rendered into
 * `maven_install` / BUILD dependency lists — so this is a plan-then-render pipeline, and the bucket
 * assignment computed here is the "plan".
 *
 * ### The variant hierarchy (example app: flavors demo/full × build types debug/release)
 *
 * A dependency is owned by the *highest* bucket whose descendant leaves all use it:
 *
 *     default                          deps common to every leaf
 *     ├── debug      covers leaves: demoDebug, fullDebug
 *     ├── release    covers leaves: demoRelease, fullRelease
 *     ├── demo       covers leaves: demoDebug, demoRelease
 *     └── full       covers leaves: fullDebug, fullRelease
 *
 *     leaves = the 4 concrete variants Gradle actually resolves; each extends one build type + one
 *     flavor (a lattice, not a tree: demoDebug extends both `debug` and `demo`, and `default`).
 *
 * ### Flow, decomposed (with inline values)
 *
 *     AggregatedDependencyResolver        resolve each leaf's closure:
 *     (resolution — feeds this)             demoDebug   = okhttp gson timber leakcanary demoSdk
 *                                           fullDebug   = okhttp gson timber leakcanary fullSdk
 *                                           demoRelease = okhttp gson timber            demoSdk
 *                                           fullRelease = okhttp gson timber            fullSdk
 *            │
 *     BucketPlacementVariantInputs.kt     describe each leaf's place in the hierarchy:
 *     (input synthesis)                     demoDebug extendsFrom {default, demo, debug}  (etc.)
 *            │
 *     [BucketPlacementGraph]              derive ancestor / descendant-leaf sets:
 *     (graph adapter)                       debug -> {demoDebug, fullDebug};  demo -> {demoDebug, demoRelease}
 *            │
 *     [DependencyBucketPlacementEngine]   the 3-stage set-math, per project:
 *     (the algorithm)                       1. default   = common to ALL leaves      = {gson, okhttp, timber}
 *                                           2. hierarchy = (common to leaves under it) minus default
 *                                                debug={leakcanary}  demo={demoSdk}  full={fullSdk}
 *                                                release={} -> dropped
 *                                           3. leaf      = leaf minus default minus ancestors
 *                                                all four leaves collapse to {} -> dropped
 *            │
 *     [MainBucketPlanner] / [TestBucketPlanner]   merge across projects; for test buckets subtract
 *     assembled by this [BucketOwnershipPlanner]  what main already covers; reconcile declared
 *                                                 metadata (excludes/overrides); assemble output:
 *                                           default -> [gson, okhttp, timber]
 *                                           debug   -> [leakcanary]
 *                                           demo    -> [demoSdk]
 *                                           full    -> [fullSdk]
 *
 * Test buckets run the *same* placement but additionally subtract whatever `main` already provides
 * (a Gradle test source set sees main's classpath for free); androidTest further subtracts what unit
 * `test` covers. "Already provided by another bucket" is decided by [Coverage].
 *
 * @see DependencyBucketPlacementEngine for the placement algorithm (stage 1-3 above)
 * @see Coverage for the "already covered" predicate that drives every subtraction
 */
package com.grab.grazel.gradle.dependencies.bucket

import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.ProjectDependencyBucket
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.LINT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.Test

internal data class OwnershipPlannerInput(
    val leafClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
    val leafUnitTestClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
    val leafAndroidTestClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
    val hierarchyBucketClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
    val testHierarchyBucketClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
    val reachableMainBucketNamesByProject: Map<String, Set<String>>,
    val lintDeps: Map<String, ResolvedDependency>,
    val declaredTestDependenciesByBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
)

/**
 * Decides, for every Gradle variant "bucket" (default classpath, hierarchy bucket such as a
 * flavor/buildType, or leaf variant) of a project, which resolved dependencies that bucket should
 * own in the generated Bazel target graph - i.e. it turns a flat, per-variant resolution result
 * into a deduplicated ownership plan where each dependency is declared exactly once, as high up
 * the variant hierarchy as its actual usage allows, and test source sets only carry what main
 * hasn't already covered. This sits between dependency resolution and Bazel target rendering: the
 * output buckets become individual `maven_install`/target dependency lists.
 *
 * Main bucket placement is delegated to [DependencyBucketPlacementEngine]; this class layers test
 * bucket planning (unit test / android test) and cross-bucket coverage subtraction on top, plus
 * reconciliation of user-declared dependency metadata (excludes, overrides) back onto the placed
 * buckets.
 */
internal class BucketOwnershipPlanner(
    private val declaredDependencyMetadata: DeclaredDependencyMetadata,
    private val precomputedKspDependencies: Set<ResolvedDependency>
) {
    /**
     * Orchestrates the three-stage plan: main buckets first (default/hierarchy/leaf), then unit
     * test buckets - which may skip any dependency already covered by main - then android test
     * buckets, which additionally skip anything unit test buckets already cover. This ordering is
     * an invariant: android test coverage subtraction depends on the already-computed unit test
     * result ([testBuckets]), so unit test buckets must be planned first.
     */
    fun plan(input: OwnershipPlannerInput): List<ResolveDependenciesResult> {
        val mainBuckets = MainBucketPlanner(declaredDependencyMetadata).plan(input)
        val aggregateMainCoveredDeps = mainBuckets.coveredDependencies()
        val testBucketPlanner = TestBucketPlanner(declaredDependencyMetadata)
        val testBuckets = testBucketPlanner.plan(
            input = input,
            variantType = Test,
            baseBucketName = TEST_VARIANT,
            leafClosures = input.leafUnitTestClosures,
            mainCoveredDepsByProject = mainBuckets.mainCoveredDepsByProject,
            aggregateMainCoveredDeps = aggregateMainCoveredDeps,
            declaredTestDependenciesByBucket = input.declaredTestDependenciesByBucket
        )
        val androidTestBuckets = testBucketPlanner.plan(
            input = input,
            variantType = AndroidTest,
            baseBucketName = ANDROID_TEST_VARIANT,
            leafClosures = input.leafAndroidTestClosures,
            mainCoveredDepsByProject = mainBuckets.mainCoveredDepsByProject,
            aggregateMainCoveredDeps = aggregateMainCoveredDeps,
            declaredTestDependenciesByBucket = input.declaredTestDependenciesByBucket,
            inheritedTestCoveredDeps = testBuckets
                .flatMap { (bucketName, deps) -> coveredDependenciesForBucket(deps, bucketName) }
        )

        return buildResults(
            input = input,
            mainBuckets = mainBuckets,
            testBuckets = testBuckets,
            androidTestBuckets = androidTestBuckets
        )
    }

    private fun buildResults(
        input: OwnershipPlannerInput,
        mainBuckets: MainBucketPlanResult,
        testBuckets: Map<String, Map<String, ResolvedDependency>>,
        androidTestBuckets: Map<String, Map<String, ResolvedDependency>>
    ): List<ResolveDependenciesResult> {
        val results = mutableListOf<ResolveDependenciesResult>()
        val reachableMainBucketsSnapshot = input.reachableMainBucketNamesByProject
            .mapValues { (_, bucketNames) -> bucketNames.toSortedSet() }

        fun buildResult(
            bucketName: String,
            deps: Map<String, ResolvedDependency>
        ): ResolveDependenciesResult {
            val kspDeps = if (bucketName == DEFAULT_VARIANT) precomputedKspDependencies else emptySet()
            return ResolveDependenciesResult(
                variantName = bucketName,
                dependencies = mapOf(
                    COMPILE.name to deps.values.toSortedSet(),
                    KSP.name to kspDeps
                ),
                reachableMainBucketsByProject = reachableMainBucketsSnapshot
            )
        }

        results.add(buildResult(DEFAULT_VARIANT, mainBuckets.defaultDeps))
        mainBuckets.hierarchyBuckets.forEach { (bucketName, deps) -> results.add(buildResult(bucketName, deps)) }
        mainBuckets.filteredPerLeafBuckets.forEach { (leaf, deps) -> results.add(buildResult(leaf, deps)) }
        testBuckets.forEach { (bucketName, deps) -> results.add(buildResult(bucketName, deps)) }
        androidTestBuckets.forEach { (bucketName, deps) -> results.add(buildResult(bucketName, deps)) }
        if (input.lintDeps.isNotEmpty()) results.add(buildResult(LINT_VARIANT, input.lintDeps))

        return results
    }
}
