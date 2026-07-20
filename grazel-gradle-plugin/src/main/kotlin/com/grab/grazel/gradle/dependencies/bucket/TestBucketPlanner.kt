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

import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.DeclaredVariantDependencyMetadata
import com.grab.grazel.gradle.dependencies.ProjectDependencyBucket
import com.grab.grazel.gradle.dependencies.mergeBucket
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.Test
import com.grab.grazel.gradle.variant.testSuffix

/**
 * Plans the unit-test and android-test buckets for every project.
 *
 * A test source set does not stand alone: its generated target depends on the corresponding main
 * target and so inherits main's entire classpath for free. A test bucket is therefore not a fresh
 * placement but a *residual* - it should declare only what main does not already provide. Planning
 * runs in two steps: the test variants are placed into their own default/hierarchy/leaf lattice by
 * the shared [DependencyBucketPlacementEngine] (the same logic main uses), then everything main
 * already covers is subtracted, leaving only genuinely test-only dependencies. Android-test
 * additionally subtracts what unit-test covers, since it inherits that source set as well.
 *
 * This subtraction is deliberately stricter than the coverage rule main applies among its own
 * buckets - it uses [CoveredDependency.canCoverTest] rather than [CoveredDependency.canCover]. Main
 * placement is a partition of a single shared classpath: a dependency appearing in two main buckets
 * is the same resolved artifact and can be disambiguated in place. A test root, by contrast, can
 * share a main root's exact resolved identity yet pull a *larger* transitive closure than main did
 * for that same artifact. Main's copy then would not actually supply those extra transitives, so
 * treating the root as "already provided" on identity alone would drop it and silently starve the
 * test target. A main dependency may therefore cover a test root only when it is itself a direct
 * root whose transitive closure is a superset of the test root's; anything short of that is kept as
 * the test bucket's own declaration.
 */
internal class TestBucketPlanner(
    private val declaredDependencyMetadata: DeclaredDependencyMetadata
) {
    private fun variantsFor(projectPath: String): List<DeclaredVariantDependencyMetadata> =
        declaredDependencyMetadata.projects[projectPath]?.variants.orEmpty()

    fun plan(
        input: OwnershipPlannerInput,
        variantType: VariantType,
        baseBucketName: String,
        leafClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        mainCoveredDepsByProject: Map<String, List<CoveredDependency>>,
        aggregateMainCoveredDeps: List<CoveredDependency>,
        declaredTestDependenciesByBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        inheritedTestCoveredDeps: List<CoveredDependency> = emptyList()
    ): Map<String, Map<String, ResolvedDependency>> {
        return plannedTestBuckets(
            testBucketPlansByProject = testBucketPlans(
                variantType = variantType,
                baseBucketName = baseBucketName,
                leafClosures = leafClosures,
                hierarchyBucketClosures = testHierarchyBucketClosuresFor(
                    input = input,
                    variantType = variantType,
                    baseBucketName = baseBucketName
                )
            ),
            baseBucketName = baseBucketName,
            mainCoveredDepsByProject = mainCoveredDepsByProject,
            aggregateMainCoveredDeps = aggregateMainCoveredDeps,
            declaredTestDependenciesByBucket = declaredTestDependenciesByBucket,
            inheritedTestCoveredDeps = inheritedTestCoveredDeps
        )
    }

    private fun testHierarchyBucketClosuresFor(
        input: OwnershipPlannerInput,
        variantType: VariantType,
        baseBucketName: String
    ): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
        val testBucketNames = typedTestBucketNames(
            variantType = variantType,
            baseBucketName = baseBucketName
        )
        return input.testHierarchyBucketClosures.filterKeys { bucket ->
            bucket.bucketName in testBucketNames
        }
    }

    private fun typedTestBucketNames(
        variantType: VariantType,
        baseBucketName: String
    ): Set<String> {
        return declaredDependencyMetadata.projects
            .values
            .asSequence()
            .flatMap { projectMetadata -> projectMetadata.variants.asSequence() }
            .filter { variant -> variant.variantType == variantType }
            .mapTo(sortedSetOf(), DeclaredVariantDependencyMetadata::name)
            .apply { add(baseBucketName) }
    }

    /**
     * A main leaf variant (e.g. `demoDebug`) has no test closure of its own; test dependencies are
     * resolved against a *test* leaf variant (e.g. `demoDebugAndroidTest`) that extends it. When
     * several declared test leaves extend the same main leaf, [minOrNull] picks the
     * lexicographically first name as a deterministic tie-break - callers only need one concrete
     * name per main leaf, and any of the candidates would resolve to the same underlying closure,
     * so the choice is arbitrary but must be stable across runs. Falls back to [baseBucketName]
     * (e.g. "test"/"androidTest") when no test leaf extends this main leaf at all.
     */
    private fun concreteTestLeafName(
        projectPath: String,
        mainLeafName: String,
        variantType: VariantType,
        baseBucketName: String
    ): String {
        return variantsFor(projectPath)
            .asSequence()
            .filter { variant -> variant.variantType == variantType }
            .filter(DeclaredVariantDependencyMetadata::androidLeafVariant)
            .filter { variant -> mainLeafName in variant.extendsFrom || variant.name == mainLeafName }
            .map(DeclaredVariantDependencyMetadata::name)
            .minOrNull()
            ?: baseBucketName
    }

    private fun concreteTestLeafClosures(
        leafClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        variantType: VariantType,
        baseBucketName: String
    ): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
        val mappedClosures = linkedMapOf<ProjectDependencyBucket, Map<String, ResolvedDependency>>()
        leafClosures.forEach { (bucket, dependencies) ->
            val testBucket = ProjectDependencyBucket(
                projectPath = bucket.projectPath,
                bucketName = concreteTestLeafName(
                    projectPath = bucket.projectPath,
                    mainLeafName = bucket.bucketName,
                    variantType = variantType,
                    baseBucketName = baseBucketName
                )
            )
            mappedClosures.mergeBucket(testBucket, dependencies)
        }
        return mappedClosures
    }

    private fun testBucketPlans(
        variantType: VariantType,
        baseBucketName: String,
        leafClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        hierarchyBucketClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
    ): Map<String, DependencyBucketPlacementPlan> {
        return DependencyBucketPlacementEngine().planByProject(
            variants = declaredDependencyMetadata.testBucketVariantsByProject(
                variantType = variantType,
                baseBucketName = baseBucketName
            ),
            hierarchyBucketClosures = hierarchyBucketClosures,
            leafClosures = concreteTestLeafClosures(
                leafClosures = leafClosures,
                variantType = variantType,
                baseBucketName = baseBucketName
            ),
            baseBucketName = baseBucketName
        )
    }

    /**
     * For each project's test bucket placement plan, subtracts every dependency that's already
     * reachable from the buckets a Gradle test source set can actually see, so only genuinely
     * test-only dependencies survive into the output. For every planned bucket this computes two
     * independent coverage views and combines them:
     *
     * - "visible" coverage ([withoutTestDependenciesCoveredBy]): deps covered by this bucket's own
     *   ancestor chain (default + hierarchy ancestors) plus any already-covered main/inherited-test
     *   deps for this bucket's own name and ancestors.
     * - "every leaf" coverage ([withoutTestDependenciesCoveredByEveryLeaf]): for a non-leaf test
     *   bucket, a dependency is only dropped if *all* of its concrete descendant test leaves
     *   independently see it covered too - a dependency covered on only some leaves must stay so
     *   those leaves that need it still get it.
     *
     * Each candidate bucket's dependencies are also remapped to an [outputBucketNameForTestBucket]
     * before merging, since several distinct internal bucket names can collapse onto the same
     * emitted test source set. Declared test dependency metadata is looked up under both the raw
     * bucket name and its output name because declarations may be recorded against either.
     *
     * Worked example, project `:app` with android-test leaves `demoDebug` and `fullDebug`:
     * - `okhttp` is already declared in the `default` bucket. It appears in the placement plan's
     *   `androidTest` (base) bucket, but since `default` is part of the "visible" coverage for the
     *   base bucket, [withoutTestDependenciesCoveredBy] drops it - it is reachable without adding it
     *   to the `androidTest` test source set.
     * - `espresso-idling-resource` is declared only under `demoDebugAndroidTest` (i.e. only the
     *   `demoDebug` leaf uses it), and is not present in `default`, `androidTest`, or the
     *   `fullDebug` leaf's closure. It survives [withoutTestDependenciesCoveredBy] (no ancestor
     *   covers it) and [withoutTestDependenciesCoveredByEveryLeaf] (not *every* leaf covers it, so
     *   it isn't dropped from its own leaf bucket), so [planTestBucket] emits it into the
     *   `demoDebugAndroidTest` output bucket.
     */
    private fun plannedTestBuckets(
        testBucketPlansByProject: Map<String, DependencyBucketPlacementPlan>,
        baseBucketName: String,
        mainCoveredDepsByProject: Map<String, List<CoveredDependency>>,
        aggregateMainCoveredDeps: List<CoveredDependency>,
        declaredTestDependenciesByBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        inheritedTestCoveredDeps: List<CoveredDependency> = emptyList()
    ): Map<String, Map<String, ResolvedDependency>> {
        val buckets = linkedMapOf<String, Map<String, ResolvedDependency>>()
        val declaredMetadataByOutputBucket = linkedMapOf<String, Map<String, ResolvedDependency>>()
        testBucketPlansByProject.toSortedMap().forEach { (projectPath, plan) ->
            val mainCoveredDeps = mainCoveredDepsByProject[projectPath].orEmpty()
            val coveredDepsByBucket = (
                mainCoveredDeps +
                    aggregateMainCoveredDeps +
                    inheritedTestCoveredDeps
                )
                .groupBy(CoveredDependency::bucketName)
            val plannedBuckets = linkedMapOf<String, Map<String, ResolvedDependency>>()

            plannedBuckets.mergeBucket(plan.baseBucketName, plan.defaultBucket)
            plan.hierarchyBuckets.toSortedMap().forEach { (bucketName, dependencies) ->
                plannedBuckets.mergeBucket(bucketName, dependencies)
            }
            plan.leafBuckets.toSortedMap().forEach { (bucketName, dependencies) ->
                plannedBuckets.mergeBucket(bucketName, dependencies)
            }
            plannedBuckets.forEach { (bucketName, dependencies) ->
                val result = planTestBucket(
                    projectPath = projectPath,
                    plan = plan,
                    bucketName = bucketName,
                    dependencies = dependencies,
                    coveredDepsByBucket = coveredDepsByBucket,
                    declaredTestDependenciesByBucket = declaredTestDependenciesByBucket
                )
                if (result != null) {
                    addDeclaredOutputMetadata(
                        declaredMetadataByOutputBucket = declaredMetadataByOutputBucket,
                        bucketName = result.outputBucketName,
                        metadata = result.declaredMetadata
                    )
                    buckets.mergeBucket(result.outputBucketName, result.dependencies)
                }
            }
        }
        return applyDeclaredMetadataByBucket(
            dependenciesByBucket = withoutMergedBaseTestDependenciesCoveredBy(
                testDependenciesByBucket = buckets.toSortedMap(),
                baseBucketName = baseBucketName,
                aggregateMainCoveredDeps = aggregateMainCoveredDeps,
                inheritedTestCoveredDeps = inheritedTestCoveredDeps
            ),
            declaredMetadataByOutputBucket = declaredMetadataByOutputBucket
        )
    }

    /**
     * Result of planning a single internal bucket within one project's placement plan: the
     * dependencies that survived coverage subtraction, remapped to their emitted output bucket
     * name, plus the slice of declared test dependency metadata that corresponds to them.
     */
    private data class TestBucketResult(
        val outputBucketName: String,
        val dependencies: Map<String, ResolvedDependency>,
        val declaredMetadata: Map<String, ResolvedDependency>
    )

    private fun coveredDepsByShortIdFor(
        coveredDepsByBucket: Map<String, List<CoveredDependency>>,
        bucketNames: Set<String>
    ): Map<String, List<CoveredDependency>> {
        return bucketNames
            .asSequence()
            .flatMap { bucketName -> coveredDepsByBucket[bucketName].orEmpty().asSequence() }
            .toList()
            .let(::groupCoveredDependenciesByShortId)
    }

    private fun planTestBucket(
        projectPath: String,
        plan: DependencyBucketPlacementPlan,
        bucketName: String,
        dependencies: Map<String, ResolvedDependency>,
        coveredDepsByBucket: Map<String, List<CoveredDependency>>,
        declaredTestDependenciesByBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
    ): TestBucketResult? {
        val outputBucketName = outputBucketNameForTestBucket(plan, bucketName)
        val testBucketNames = testBucketNamesForTestBucket(
            plan = plan,
            bucketName = bucketName
        )
        val visibleMainBucketNames = visibleMainBucketNamesForTestBucket(
            plan = plan,
            testBucketNames = testBucketNames
        )
        val visibleCoveredDepsByShortId = coveredDepsByShortIdFor(
            coveredDepsByBucket,
            visibleMainBucketNames + testBucketNames
        )
        val leafCoveredDepsByShortId = concreteTestLeafNamesFor(
            plan = plan,
            bucketName = bucketName
        )
            .map { leafName ->
                val leafTestBucketNames = testBucketNamesForTestBucket(
                    plan = plan,
                    bucketName = leafName
                )
                val leafVisibleMainBucketNames = visibleMainBucketNamesForTestBucket(
                    plan = plan,
                    testBucketNames = leafTestBucketNames
                )
                coveredDepsByShortIdFor(
                    coveredDepsByBucket,
                    leafVisibleMainBucketNames + leafTestBucketNames
                )
            }
        val declaredTestDependencies = declaredTestDependenciesByBucket[
            ProjectDependencyBucket(projectPath, bucketName)
        ].orEmpty() + declaredTestDependenciesByBucket[
            ProjectDependencyBucket(projectPath, outputBucketName)
        ].orEmpty()
        val visibleMainOnlyDependencies = withoutTestDependenciesCoveredBy(
            testDependencies = dependencies,
            coveredByShortId = visibleCoveredDepsByShortId,
            declaredTestDependencies = declaredTestDependencies
        )
        val testOnlyDependencies = withoutTestDependenciesCoveredByEveryLeaf(
            testDependencies = visibleMainOnlyDependencies,
            leafCoveredDepsByShortId = leafCoveredDepsByShortId,
            declaredTestDependencies = declaredTestDependencies
        )
            .toSortedMap()
        return if (testOnlyDependencies.isNotEmpty()) {
            TestBucketResult(
                outputBucketName = outputBucketName,
                dependencies = testOnlyDependencies,
                declaredMetadata = declaredTestDependencies.filterKeys { shortId ->
                    shortId in testOnlyDependencies
                }
            )
        } else {
            null
        }
    }

    private fun testBucketNamesForTestBucket(
        plan: DependencyBucketPlacementPlan,
        bucketName: String
    ): Set<String> = setOf(bucketName) + plan.bucketAncestors[bucketName].orEmpty()

    private fun visibleMainBucketNamesForTestBucket(
        plan: DependencyBucketPlacementPlan,
        testBucketNames: Set<String>
    ): Set<String> {
        return buildSet {
            add(DEFAULT_VARIANT)
            testBucketNames.forEach { testBucketName ->
                if (testBucketName == plan.baseBucketName) return@forEach
                add(testBucketName)
            }
        }
    }

    private fun concreteTestLeafNamesFor(
        plan: DependencyBucketPlacementPlan,
        bucketName: String
    ): Set<String> {
        return plan.bucketDescendantLeaves[bucketName]
            .orEmpty()
            .ifEmpty {
                if (bucketName in plan.leafAncestors) setOf(bucketName) else emptySet()
            }
    }

    /**
     * A placement plan may contain internal bucket names (default, hierarchy, leaf) that don't
     * correspond 1:1 to an emitted test source set. This decides where a bucket's dependencies
     * should actually surface:
     * - the plan's own base bucket name, or any bucket already typed as this test variant, is
     *   emitted unchanged;
     * - otherwise, try suffixing the name for this test variant type (e.g. `demo` ->
     *   `demoAndroidTest`) - this is how a main hierarchy bucket maps to its corresponding typed
     *   test bucket;
     * - if that suffixed name isn't itself a real typed test bucket (no such variant was declared),
     *   collapse everything down to the plan's base bucket name rather than emitting a name nothing
     *   else recognizes.
     */
    private fun outputBucketNameForTestBucket(
        plan: DependencyBucketPlacementPlan,
        bucketName: String
    ): String {
        if (bucketName == plan.baseBucketName || isTypedTestBucket(plan, bucketName)) {
            return bucketName
        }
        val scopedBucketName = bucketName + testVariantTypeForBaseBucket(plan).testSuffix
        return if (isTypedTestBucket(plan, scopedBucketName)) {
            scopedBucketName
        } else {
            plan.baseBucketName
        }
    }

    private fun isTypedTestBucket(
        plan: DependencyBucketPlacementPlan,
        bucketName: String
    ): Boolean {
        return plan.variantTypesByBucketName[bucketName] == testVariantTypeForBaseBucket(plan)
    }

    private fun testVariantTypeForBaseBucket(plan: DependencyBucketPlacementPlan): VariantType {
        return when (plan.baseBucketName) {
            TEST_VARIANT -> Test
            ANDROID_TEST_VARIANT -> AndroidTest
            else -> error("Unsupported test base bucket ${plan.baseBucketName}")
        }
    }
}

/**
 * The stricter, test-side counterpart to [Coverage.subtract] - a two-pass filter over a test
 * bucket's dependencies against the deps already covered elsewhere:
 *
 * 1. [Coverage.subtract] does the generic subtraction, which can be too eager -
 *    it may drop a *direct* test dependency merely because some covering bucket happens to also
 *    resolve the same shortId, even if that covering entry can't actually satisfy this dependency's
 *    specific requirements (excludes, jetifier, exact closure).
 * 2. So any direct dependency the first pass removed is re-checked with [CoveredDependency.canCoverTest],
 *    which additionally recognizes deps whose transitive closure is satisfied only with the help of
 *    "scoped sibling closure" reasoning (see [scopedSiblingClosureDependenciesByShortId]); anything
 *    that still isn't genuinely coverable is restored.
 *
 * The final result re-applies [CoveredDependency.canCoverTest] to the union of both passes, so a dependency
 * is dropped only when it is truly coverable by this rule, not merely by shortId collision.
 */
private fun withoutTestDependenciesCoveredBy(
    testDependencies: Map<String, ResolvedDependency>,
    coveredByShortId: Map<String, List<CoveredDependency>>,
    declaredTestDependencies: Map<String, ResolvedDependency>
): Map<String, ResolvedDependency> {
    val filtered = Coverage.ofGrouped(coveredByShortId).subtract(testDependencies)
    val scopedSiblingClosureDependenciesByShortId = scopedSiblingClosureDependenciesByShortId(testDependencies)
    val restoredDependencies = testDependencies.filter { (shortId, dependency) ->
        dependency.direct && shortId !in filtered && !coveredByShortId[shortId].orEmpty().any { covered ->
            covered.canCoverTest(
                candidate = dependency,
                declaredTestDependency = declaredTestDependencies[shortId],
                scopedSiblingClosureDependencies = scopedSiblingClosureDependenciesByShortId[shortId].orEmpty()
            )
        }
    }
    return (filtered + restoredDependencies).filterNot { (shortId, dependency) ->
        val declaredTestDependency = declaredTestDependencies[shortId]
        coveredByShortId[dependency.shortId].orEmpty().any { covered ->
            covered.canCoverTest(
                candidate = dependency,
                declaredTestDependency = declaredTestDependency,
                scopedSiblingClosureDependencies = scopedSiblingClosureDependenciesByShortId[shortId].orEmpty()
            )
        }
    }
}

/**
 * For a non-leaf test bucket, a dependency can only be safely dropped if *every* concrete
 * descendant test leaf independently covers it too - dropping it based on only some leaves
 * covering it would silently starve the leaves that don't, since a non-leaf bucket's output is
 * shared by all its leaves. Hence the `all { ... }` (not `any`): this is the "every leaf must
 * agree" closure invariant, and reversing it to `any` would over-remove dependencies that some
 * leaves still need directly.
 */
private fun withoutTestDependenciesCoveredByEveryLeaf(
    testDependencies: Map<String, ResolvedDependency>,
    leafCoveredDepsByShortId: List<Map<String, List<CoveredDependency>>>,
    declaredTestDependencies: Map<String, ResolvedDependency>
): Map<String, ResolvedDependency> {
    if (testDependencies.isEmpty() || leafCoveredDepsByShortId.isEmpty()) return testDependencies
    val scopedSiblingClosureDependenciesByShortId = scopedSiblingClosureDependenciesByShortId(testDependencies)
    return testDependencies.filterNot { (shortId, dependency) ->
        val declaredTestDependency = declaredTestDependencies[shortId]
        leafCoveredDepsByShortId.all { coveredByShortId ->
            coveredByShortId[shortId].orEmpty().any { covered ->
                covered.canCoverTest(
                    candidate = dependency,
                    declaredTestDependency = declaredTestDependency,
                    scopedSiblingClosureDependencies = scopedSiblingClosureDependenciesByShortId[shortId].orEmpty()
                )
            }
        }
    }
}

/**
 * A cleanup pass that runs *after* all per-project test buckets have been merged into
 * [testDependenciesByBucket]: the merge can reintroduce a dependency into the base test bucket
 * (e.g. plain "test") that is already covered by default or by an inherited test bucket, because
 * that coverage information wasn't visible while each project's bucket was being filtered in
 * isolation. Only the base bucket is re-checked here (hierarchy/leaf buckets were already filtered
 * per-project against their own ancestors). Android test additionally sees unit test's dependencies
 * as coverage - a dependency already provided via `test` doesn't need to be redeclared for
 * `androidTest` - which is why [visibleCoveredBucketNames] special-cases [ANDROID_TEST_VARIANT].
 */
private fun withoutMergedBaseTestDependenciesCoveredBy(
    testDependenciesByBucket: Map<String, Map<String, ResolvedDependency>>,
    baseBucketName: String,
    aggregateMainCoveredDeps: List<CoveredDependency>,
    inheritedTestCoveredDeps: List<CoveredDependency>
): Map<String, Map<String, ResolvedDependency>> {
    val baseBucket = testDependenciesByBucket[baseBucketName] ?: return testDependenciesByBucket
    val visibleCoveredBucketNames = buildSet {
        add(DEFAULT_VARIANT)
        if (baseBucketName == ANDROID_TEST_VARIANT) {
            add(TEST_VARIANT)
        }
    }
    val coveredByShortId = (aggregateMainCoveredDeps + inheritedTestCoveredDeps)
        .filter { covered -> covered.bucketName in visibleCoveredBucketNames }
        .let(::groupCoveredDependenciesByShortId)
    if (coveredByShortId.isEmpty()) return testDependenciesByBucket

    val filteredBaseBucket = withoutTestDependenciesCoveredBy(
        testDependencies = baseBucket,
        coveredByShortId = coveredByShortId,
        declaredTestDependencies = emptyMap()
    )
        .toSortedMap()
    if (filteredBaseBucket == baseBucket) return testDependenciesByBucket

    return testDependenciesByBucket.toMutableMap()
        .apply {
            if (filteredBaseBucket.isEmpty()) {
                remove(baseBucketName)
            } else {
                this[baseBucketName] = filteredBaseBucket
            }
        }
        .toSortedMap()
}

/**
 * A transitive dependency notation that appears in more than one root's own declared-or-transitive
 * closure within the same bucket is a "sibling-shared" transitive: several direct roots each
 * (partially) pull it in. Counting occurrences and comparing against 1 (self) + (1 if already in
 * that root's own closure) lets `canCoverInheritedTestRoot` treat such a notation as effectively
 * part of a root's closure even though it isn't literally listed there - covering the common case
 * where a covering bucket's root closure doesn't itself list a transitive dependency that a sibling
 * root in this bucket already accounts for. Without this, coverage comparisons would be too strict
 * and would keep dependencies that are, in practice, already fully provided.
 */
private fun scopedSiblingClosureDependenciesByShortId(
    dependencies: Map<String, ResolvedDependency>
): Map<String, Set<String>> {
    val closureDependencyCounts = dependencies.values
        .asSequence()
        .flatMap { dependency ->
            sequenceOf(dependency.toDependencyNotation()) + dependency.dependencies.asSequence()
        }
        .groupingBy { dependencyNotation -> dependencyNotation }
        .eachCount()
    if (closureDependencyCounts.isEmpty()) return emptyMap()

    return dependencies.mapValues { (_, dependency) ->
        closureDependencyCounts
            .asSequence()
            .filter { (dependencyNotation, count) ->
                val candidateContribution =
                    if (dependencyNotation == dependency.toDependencyNotation()) 1 else 0
                val candidateClosureContribution = if (dependencyNotation in dependency.dependencies) 1 else 0
                count > candidateContribution + candidateClosureContribution
            }
            .mapTo(sortedSetOf()) { (dependencyNotation, _) -> dependencyNotation }
    }
}

private fun ResolvedDependency.toDependencyNotation(): String =
    "$id:$repository:$requiresJetifier:$jetifierSource"
