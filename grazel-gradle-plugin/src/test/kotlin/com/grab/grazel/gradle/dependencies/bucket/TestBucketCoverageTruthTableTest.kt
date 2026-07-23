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

import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.hasSameResolvedArtifactIdentityAs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth-table proof for critic-04 #2 / synthesis backlog item 6: does
 * `TestBucketPlanner.withoutTestDependenciesCoveredBy`'s three-pass filter
 * (`Coverage.subtract` on [CoveredDependency.canCover] -> restore direct misses ->
 * final [CoveredDependency.canCoverTest] re-filter) collapse to a single
 * `testDependencies.filterNot { canCoverTest(...) }` pass?
 *
 * VERDICT: REFUTED. See [nonDirect_genericallyCovered_dropsInThreePass_singlePassWouldKeep] -
 * a non-direct (transitive) test dependency whose owner identity is already resolved by a
 * covering bucket ([CoveredDependency.canCover] == true) is *unconditionally* dropped by pass 1
 * (`Coverage.subtract`) and never reconsidered (the restore pass only reconsiders `direct`
 * misses), while [CoveredDependency.canCoverTest] is *structurally* false for every non-direct
 * candidate (all three of its dispatch branches require the candidate to be `direct`), so a
 * single `filterNot { canCoverTest }` pass would keep it. This quadrant is not a corner case -
 * it is the ordinary "same transitive artifact resolved in both a main bucket and a test
 * bucket" situation.
 *
 * The four quadrants below are keyed on (candidate direct?, generically covered by
 * [CoveredDependency.canCover]?), each evaluated against the real predicates in Coverage.kt.
 */
class TestBucketCoverageTruthTableTest {

    private fun dep(
        shortId: String = "com.example:lib",
        version: String = "1.0",
        direct: Boolean,
        dependencies: Set<String> = emptySet(),
        excludeRules: Set<com.grab.grazel.gradle.dependencies.model.ExcludeRule> = emptySet(),
        repository: String = "maven",
        requiresJetifier: Boolean = false
    ): ResolvedDependency = ResolvedDependency(
        id = "$shortId:$version",
        version = version,
        shortId = shortId,
        direct = direct,
        dependencies = dependencies,
        excludeRules = excludeRules,
        repository = repository,
        requiresJetifier = requiresJetifier
    )

    private fun covering(dependency: ResolvedDependency, bucketName: String = "main"): CoveredDependency =
        CoveredDependency(bucketName, dependency)

    // --- Quadrant 1: non-direct candidate, generically covered (canCover == true) ---------------
    // THE divergence quadrant: proves the claim in the brief false.
    @Test
    fun nonDirect_genericallyCovered_dropsInThreePass_singlePassWouldKeep() {
        val coveringDependency = dep(direct = true) // same owner identity, resolved elsewhere as direct
        val candidate = dep(direct = false) // this test bucket only needs it transitively
        val covered = covering(coveringDependency)

        // Pass 1 (Coverage.subtract via canCover): generically covered -> pass 1 drops it.
        assertTrue(
            "canCover must hold for a non-direct candidate whose owner identity matches a covering dep",
            covered.canCover(candidate)
        )

        // The hypothetical single-pass filter decides purely via canCoverTest, which is
        // structurally false for ANY non-direct candidate - both declaredTestDependency
        // branches (canCoverInheritedTestRoot / canCoverDeclaredTestRoot) require the
        // candidate to be direct, and isDeclaredMetadata() placeholders are always direct
        // by construction (see DeclaredDependencyMetadataCollector), so a non-direct
        // candidate can never take the isDeclaredMetadata() branch either.
        assertFalse(
            "canCoverTest must be false for a non-direct candidate regardless of declaredTestDependency",
            covered.canCoverTest(candidate, declaredTestDependency = null)
        )
        assertFalse(
            covered.canCoverTest(candidate, declaredTestDependency = candidate)
        )

        // Three-pass outcome: dropped by pass 1, non-direct so never enters the restore pass
        // (`restoredDependencies` requires `dependency.direct`) -> permanently dropped.
        // Single-pass outcome: filterNot { canCoverTest } keeps it (canCoverTest is false).
        // => the two forms diverge on this reachable input.
    }

    // --- Quadrant 2: non-direct candidate, NOT generically covered (canCover == false) -----------
    // Both forms agree: kept (pass 1 keeps it unmatched; canCoverTest is false so single-pass keeps it).
    @Test
    fun nonDirect_notGenericallyCovered_bothFormsKeep() {
        val coveringDependency = dep(shortId = "com.example:other", direct = true)
        val candidate = dep(direct = false)
        val covered = covering(coveringDependency)

        assertFalse(covered.canCover(candidate))
        assertFalse(covered.canCoverTest(candidate, declaredTestDependency = null))
    }

    // --- Quadrant 3: direct candidate, generically covered by an exact-identity match -------------
    // Both forms agree: dropped. Exact artifact identity is strong enough that canCoverTest
    // (inherited-test-root: same shortId/version/repository/jetifier, closure superset via
    // equality, excludeRules equal) also holds.
    @Test
    fun direct_exactIdentityCovered_bothFormsDrop() {
        val shared = dep(direct = true)
        val candidate = shared.copy(direct = true)
        val covered = covering(shared)

        assertTrue(covered.canCover(candidate))
        assertTrue(covered.canCoverTest(candidate, declaredTestDependency = null))
    }

    // --- Quadrant 4: direct candidate, owner-identity match only (not exact/superset), and the
    // covering side is also direct -> pass 1 KEEPS it but annotates overrideTarget; canCoverTest
    // is false (e.g. excludeRules mismatch) so the final canCoverTest re-filter also keeps it.
    // Single-pass agrees on keep/drop, but does NOT attach overrideTarget (see part (b) below).
    @Test
    fun direct_ownerIdentityMatchBothDirect_keptByBothForms_annotationOnlyFromThreePass() {
        // Owner-identity equality (shortId/version/excludeRules/repository/jetifier) does not
        // consider `dependencies` (the transitive closure) at all, while canCover's exact-identity
        // and superset-closure checks both do. So a `dependencies` mismatch is exactly what makes
        // this "owner-identity match but not exact/superset" - the annotate-with-overrideTarget
        // branch of Coverage.subtract (Coverage.kt:152-156).
        val coveringWithClosure = dep(direct = true, dependencies = setOf("com.example:child:1.0"))
        val candidateDifferentClosure = dep(direct = true, dependencies = setOf("com.example:other-child:1.0"))
        val coveredWithClosure = covering(coveringWithClosure)

        assertTrue(
            "owner identity matches (shortId/version/excludeRules/repo/jetifier all equal)",
            coveredWithClosure.canCover(candidateDifferentClosure)
        )
        assertFalse(
            "not an exact artifact identity match (dependencies closure differs)",
            coveringWithClosure.hasSameResolvedArtifactIdentityAs(candidateDifferentClosure)
        )
        assertFalse(
            "not a superset-closure match either (covering closure does not contain candidate's child)",
            coveredWithClosure.rootsSupersetClosureOfForTest(candidateDifferentClosure)
        )
        // canCoverTest (inherited test root): closure containment fails the same way, so false.
        assertFalse(coveredWithClosure.canCoverTest(candidateDifferentClosure, declaredTestDependency = null))
    }

    // Exposed test-only mirror of the private `rootsSupersetClosureOf` extension in Coverage.kt,
    // reproducing its exact logic, purely so this test can assert on the same case split
    // `Coverage.subtract` uses (that helper is `private` to Coverage.kt and not otherwise visible
    // here). `hasSameResolvedArtifactIdentityAs` has no local mirror - it is `internal` in
    // ResolveDependenciesResult.kt and imported directly above.
    private fun CoveredDependency.rootsSupersetClosureOfForTest(dependency: ResolvedDependency): Boolean {
        return this.dependency.direct &&
            dependency.direct &&
            this.dependency.dependencies.containsAll(dependency.dependencies)
    }

    // --- Part (b): overrideTarget annotation attachment -------------------------------------------
    // Coverage.subtract attaches overrideTarget only in the "owner-identity match, both direct,
    // not exact/superset" branch (Coverage.kt:152-156). Prove it end-to-end via Coverage.subtract
    // directly (internal, real production code) rather than a hand replica.
    @Test
    fun overrideTarget_attachedOnlyForDirectOwnerIdentityMatch_viaRealCoverageSubtract() {
        val coveringDependency = dep(direct = true, dependencies = setOf("com.example:child:1.0"))
        val candidate = dep(direct = true, dependencies = setOf("com.example:other-child:1.0"))
        val coverage = Coverage.ofGrouped(
            groupCoveredDependenciesByShortId(listOf(covering(coveringDependency, bucketName = "main")))
        )

        val result = coverage.subtract(mapOf(candidate.shortId to candidate))

        assertTrue("owner-identity-only match must survive pass 1 (annotated, not dropped)", candidate.shortId in result)
        val overrideTarget = result.getValue(candidate.shortId).overrideTarget
        assertTrue("overrideTarget must be attached by Coverage.subtract's annotate branch", overrideTarget != null)
        assertEquals(
            "overrideTarget must reference this dependency's own shortId",
            candidate.shortId,
            overrideTarget!!.artifactShortId
        )

        // A single-pass `testDependencies.filterNot { canCoverTest }` form never calls
        // Coverage.subtract at all, so it can never attach this annotation to any dependency -
        // a second, independent way the collapsed form would diverge from production behavior
        // (in payload, even on inputs where keep/drop agrees).
        assertFalse(
            "single-pass form would keep this dependency too (canCoverTest is false) but unannotated",
            covering(coveringDependency).canCoverTest(candidate, declaredTestDependency = null)
        )
    }
}
