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

import com.grab.grazel.gradle.dependencies.model.OverrideTarget
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.hasSameResolvedArtifactIdentityAs
import com.grab.grazel.gradle.dependencies.model.hasSameResolvedOwnerIdentityAs

internal data class CoveredDependency(
    val bucketName: String,
    val dependency: ResolvedDependency
) {
    /**
     * The base "is this dependency already covered" predicate all higher-level coverage rules in this
     * cluster build on: either the covering entry shares the same resolved owner identity
     * ([hasSameResolvedOwnerIdentityAs]), or it's a real (non-placeholder) direct dependency that
     * matches a declared-metadata placeholder's identity ([canCoverDeclaredPlaceholder]) - letting a
     * real resolution stand in for a placeholder that only carries user-declared metadata. The
     * direct/indirect asymmetry (`!candidate.direct || dependency.direct`) means a transitive
     * dependency can be covered by either a direct or transitive entry, but a direct (root) dependency
     * can only be covered by another direct entry - a transitive resolution is not strong enough
     * evidence that a root usage is redundant.
     */
    internal fun canCover(candidate: ResolvedDependency): Boolean {
        return (dependency.hasSameResolvedOwnerIdentityAs(candidate) ||
            dependency.canCoverDeclaredPlaceholder(candidate)) &&
            (!candidate.direct || dependency.direct)
    }

    /**
     * Dispatches to one of three different coverage rules depending on what kind of dependency is
     * being checked, since "covered" means something different in each case:
     * - a declared-metadata placeholder ([ResolvedDependency.isDeclaredMetadata]) is only covered by
     *   another direct declared placeholder with the same identity/version, and exclude rules that
     *   either agree or are absent on the candidate side ([canCoverDeclaredTestMetadata]) - it carries
     *   no real artifact to resolve against;
     * - an undeclared (inherited) direct root is covered only if a candidate matches its full identity
     *   and closure is a superset ([canCoverInheritedTestRoot]) - there's no user declaration to relax
     *   the check with;
     * - a declared test root additionally requires the *declared* dependency's exclude rules (not just
     *   the candidate's) to agree ([canCoverDeclaredTestRoot]), since the user's own declaration is the
     *   authoritative source of exclude intent for that root.
     *
     * Every higher-level test-bucket coverage/closure computation in this file ultimately routes
     * through this dispatch, so getting any one branch wrong misattributes dependencies across bucket
     * boundaries.
     */
    internal fun canCoverTest(
        dependency: ResolvedDependency,
        declaredTestDependency: ResolvedDependency?,
        scopedSiblingClosureDependencies: Set<String> = emptySet()
    ): Boolean {
        return when {
            dependency.isDeclaredMetadata() -> canCoverDeclaredTestMetadata(dependency)
            declaredTestDependency == null -> canCoverInheritedTestRoot(
                dependency = dependency,
                scopedSiblingClosureDependencies = scopedSiblingClosureDependencies
            )
            else -> canCoverDeclaredTestRoot(
                dependency = dependency,
                declaredDependency = declaredTestDependency,
                scopedSiblingClosureDependencies = scopedSiblingClosureDependencies
            )
        }
    }
}

internal class Coverage private constructor(
    private val byShortId: Map<String, List<CoveredDependency>>
) {

    companion object {
        fun of(covered: Iterable<CoveredDependency>): Coverage =
            Coverage(groupCoveredDependenciesByShortId(covered))

        fun ofGrouped(byShortId: Map<String, List<CoveredDependency>>): Coverage =
            Coverage(byShortId)
    }

    /**
     * The foundational set-subtraction primitive reused by every higher-level coverage/closure rule in
     * this cluster: for each dependency, finds among the candidates that [CoveredDependency.canCover]
     * it the *best* kind of match, and reacts differently depending on which kind won:
     * - if no candidate [CoveredDependency.canCover]s the dependency at all, it is kept unchanged;
     * - an exact artifact-identity match ([hasSameResolvedArtifactIdentityAs]) or a closure
     *   superset match ([rootsSupersetClosureOf]) both fully subtract the dependency - either is
     *   strong enough evidence the covering bucket genuinely already provides it;
     * - otherwise, if only a same-owner-identity match was found and both sides are direct, the
     *   dependency survives but is annotated with an [OverrideTarget] pointing at its cover, so
     *   downstream rendering can defer to the covering bucket's version instead of duplicating a
     *   declaration;
     * - any other covered case (a same-owner-identity match where the two sides are not both direct)
     *   is subtracted (dropped), not kept.
     *
     * Preferring exact-identity, then superset-closure, then first-match (in that order) - rather than
     * whichever candidate happened to be found first - is itself an invariant: it ensures the strongest
     * available evidence decides the outcome even when a bucket has multiple same-shortId candidates.
     */
    fun subtract(dependenciesByShortId: Map<String, ResolvedDependency>): Map<String, ResolvedDependency> {
        return dependenciesByShortId.mapNotNull { (shortId, dependency) ->
            var firstCoveredDependency: CoveredDependency? = null
            var exactCoveredDependency: CoveredDependency? = null
            var supersetClosureCoveredDependency: CoveredDependency? = null

            byShortId[shortId].orEmpty().forEach { covered ->
                if (covered.canCover(dependency)) {
                    if (firstCoveredDependency == null) {
                        firstCoveredDependency = covered
                    }
                    if (
                        exactCoveredDependency == null &&
                        covered.dependency.hasSameResolvedArtifactIdentityAs(dependency)
                    ) {
                        exactCoveredDependency = covered
                    }
                    if (
                        supersetClosureCoveredDependency == null &&
                        covered.rootsSupersetClosureOf(dependency)
                    ) {
                        supersetClosureCoveredDependency = covered
                    }
                }
            }
            val coveredDependency = exactCoveredDependency
                ?: supersetClosureCoveredDependency
                ?: firstCoveredDependency
            when {
                coveredDependency == null -> shortId to dependency
                exactCoveredDependency != null -> null
                supersetClosureCoveredDependency != null -> null
                dependency.direct && coveredDependency.dependency.direct -> {
                    shortId to dependency.copy(
                        overrideTarget = dependency.overrideTarget ?: coveredDependency.toOverrideTarget()
                    )
                }
                else -> null
            }
        }
            .toMap()
    }
}

internal fun groupCoveredDependenciesByShortId(
    coveredDependencies: Iterable<CoveredDependency>
): Map<String, List<CoveredDependency>> =
    coveredDependencies.groupBy { it.dependency.shortId }

private fun ResolvedDependency.canCoverDeclaredPlaceholder(dependency: ResolvedDependency): Boolean {
    return direct &&
        !isDeclaredMetadata() &&
        dependency.direct &&
        dependency.isDeclaredMetadata() &&
        shortId == dependency.shortId &&
        version == dependency.version &&
        requiresJetifier == dependency.requiresJetifier &&
        jetifierSource == dependency.jetifierSource &&
        dependency.excludeRules == excludeRules
}

private fun CoveredDependency.rootsSupersetClosureOf(dependency: ResolvedDependency): Boolean {
    return this.dependency.direct &&
        dependency.direct &&
        this.dependency.dependencies.containsAll(dependency.dependencies)
}

private fun CoveredDependency.canCoverDeclaredTestMetadata(dependency: ResolvedDependency): Boolean {
    return this.dependency.direct &&
        dependency.isDeclaredMetadata() &&
        this.dependency.shortId == dependency.shortId &&
        this.dependency.version == dependency.version &&
        (dependency.excludeRules.isEmpty() || dependency.excludeRules == this.dependency.excludeRules)
}

/**
 * The load-bearing equality rule for an undeclared (no user override) direct root dependency:
 * covered requires the *exact same* resolved identity (shortId, version, repository, jetifier
 * requirement/source) - any of those differing means the covering bucket resolved a materially
 * different artifact that cannot stand in for this one - plus a closure-superset check so the
 * covering root's transitive closure (extended with [scopedSiblingClosureDependencies] to account
 * for sibling-shared transitives) must include everything this dependency transitively needs.
 * Exclude rules are only compared when the candidate dependency declares some: an empty exclude
 * set on the candidate is treated as compatible with anything. Both directions ([this] and
 * [dependency]) must be direct: a transitive dependency can't cover, or be covered as, a root by
 * this rule.
 */
private fun CoveredDependency.canCoverInheritedTestRoot(
    dependency: ResolvedDependency,
    scopedSiblingClosureDependencies: Set<String> = emptySet()
): Boolean {
    return this.dependency.direct &&
        dependency.direct &&
        this.dependency.shortId == dependency.shortId &&
        this.dependency.version == dependency.version &&
        this.dependency.repository == dependency.repository &&
        this.dependency.requiresJetifier == dependency.requiresJetifier &&
        this.dependency.jetifierSource == dependency.jetifierSource &&
        (this.dependency.dependencies + scopedSiblingClosureDependencies).containsAll(dependency.dependencies) &&
        (dependency.excludeRules.isEmpty() || dependency.excludeRules == this.dependency.excludeRules)
}

private fun CoveredDependency.canCoverDeclaredTestRoot(
    dependency: ResolvedDependency,
    declaredDependency: ResolvedDependency,
    scopedSiblingClosureDependencies: Set<String> = emptySet()
): Boolean {
    return canCoverInheritedTestRoot(
        dependency = dependency,
        scopedSiblingClosureDependencies = scopedSiblingClosureDependencies
    ) &&
        (declaredDependency.excludeRules.isEmpty() || declaredDependency.excludeRules == this.dependency.excludeRules)
}

/**
 * Guards against wrongly promoting a dependency into the inferred default bucket: even if a
 * dependency looks common to every leaf (and so is a candidate for [intersectByBucketOwner]'s
 * default inference), it should stay out of default when some more specific, non-default hierarchy
 * bucket already owns that same identity - default is meant to hold what's truly shared, not
 * shadow a more specific bucket's explicit ownership. The exception: if [hierarchyDefaultDeps]
 * (the *explicitly* declared default-bucket dependencies) itself already claims the same owner
 * identity, default's own explicit declaration takes precedence and the dependency is kept.
 */
internal fun withoutDependenciesOwnedByNonDefaultHierarchy(
    dependenciesByShortId: Map<String, ResolvedDependency>,
    hierarchyDefaultDeps: Map<String, ResolvedDependency>,
    nonDefaultHierarchyDependencies: Iterable<ResolvedDependency>
): Map<String, ResolvedDependency> {
    val nonDefaultByShortId = nonDefaultHierarchyDependencies.groupBy { it.shortId }
    return dependenciesByShortId.filterValues { dependency ->
        val hasSameNonDefaultOwner = nonDefaultByShortId[dependency.shortId]
            .orEmpty()
            .any { nonDefaultDependency ->
                nonDefaultDependency.hasSameResolvedOwnerIdentityAs(dependency)
            }
        !hasSameNonDefaultOwner ||
            hierarchyDefaultDeps[dependency.shortId]?.hasSameResolvedOwnerIdentityAs(dependency) == true
    }
}

/**
 * Computes the dependencies common to every given leaf closure, keyed by shortId - but a shortId
 * intersection alone isn't enough: a shared shortId across leaves could still refer to differently
 * configured resolutions (different version, excludes, jetifier settings, ...). So each candidate
 * is additionally required to be owner-identity-equal ([hasSameResolvedOwnerIdentityAs]) across
 * *every* closure it appears in before being accepted - only then is it genuinely the same
 * dependency everywhere, and safe to infer as a candidate for promotion to a shared bucket.
 */
internal fun intersectByBucketOwner(
    closures: Iterable<Map<String, ResolvedDependency>>
): Map<String, ResolvedDependency> {
    val closureList = closures.toList()
    if (closureList.isEmpty()) return emptyMap()
    val firstClosure = closureList.first()
    val intersectionIds = firstClosure.keys.toMutableSet()
    closureList.drop(1).forEach { closure ->
        intersectionIds.retainAll(closure.keys)
    }
    return intersectionIds.mapNotNull { id ->
        val candidate = firstClosure[id]!!
        if (closureList.all { closure -> closure[id]?.hasSameResolvedOwnerIdentityAs(candidate) == true }) {
            id to candidate
        } else {
            null
        }
    }.toMap()
}

internal fun coveredDependenciesForBucket(
    dependenciesByShortId: Map<String, ResolvedDependency>,
    bucketName: String
): List<CoveredDependency> {
    return dependenciesByShortId.values.map { dependency -> CoveredDependency(bucketName, dependency) }
}

/**
 * The covered dependencies of a whole plan: its base bucket plus every hierarchy and leaf bucket,
 * each tagged with its bucket name. Shared by the main and placement-plan covered-dependency walks.
 */
internal fun allCoveredDependencies(
    baseBucketName: String,
    defaultBucket: Map<String, ResolvedDependency>,
    hierarchyBuckets: Map<String, Map<String, ResolvedDependency>>,
    leafBuckets: Map<String, Map<String, ResolvedDependency>>
): List<CoveredDependency> = buildList {
    addAll(coveredDependenciesForBucket(defaultBucket, baseBucketName))
    hierarchyBuckets.forEach { (bucketName, deps) -> addAll(coveredDependenciesForBucket(deps, bucketName)) }
    leafBuckets.forEach { (bucketName, deps) -> addAll(coveredDependenciesForBucket(deps, bucketName)) }
}

private fun CoveredDependency.toOverrideTarget(): OverrideTarget {
    return mavenOverrideTarget(dependency.shortId, bucketName)
}
