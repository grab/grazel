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
)

internal fun withoutDependenciesCoveredBy(
    dependenciesByShortId: Map<String, ResolvedDependency>,
    coveredDependencies: Iterable<CoveredDependency>
): Map<String, ResolvedDependency> =
    withoutDependenciesCoveredByShortId(
        dependenciesByShortId = dependenciesByShortId,
        coveredByShortId = groupCoveredDependenciesByShortId(coveredDependencies)
    )

internal fun groupCoveredDependenciesByShortId(
    coveredDependencies: Iterable<CoveredDependency>
): Map<String, List<CoveredDependency>> =
    coveredDependencies.groupBy { it.dependency.shortId }

/**
 * The foundational set-subtraction primitive reused by every higher-level coverage/closure rule in
 * this cluster: for each dependency, finds among the candidates that [CoveredDependency.canCover]
 * it the *best* kind of match, and reacts differently depending on which kind won:
 * - an exact artifact-identity match ([hasSameResolvedArtifactIdentityAs]) or a closure
 *   superset match ([rootsSupersetClosureOf]) both fully subtract the dependency - either is
 *   strong enough evidence the covering bucket genuinely already provides it;
 * - otherwise, if only a same-owner-identity match was found and both sides are direct, the
 *   dependency survives but is annotated with an [OverrideTarget] pointing at its cover, so
 *   downstream rendering can defer to the covering bucket's version instead of duplicating a
 *   declaration;
 * - anything else falls through and is kept as-is.
 *
 * Preferring exact-identity, then superset-closure, then first-match (in that order) - rather than
 * whichever candidate happened to be found first - is itself an invariant: it ensures the strongest
 * available evidence decides the outcome even when a bucket has multiple same-shortId candidates.
 */
internal fun withoutDependenciesCoveredByShortId(
    dependenciesByShortId: Map<String, ResolvedDependency>,
    coveredByShortId: Map<String, List<CoveredDependency>>
): Map<String, ResolvedDependency> {
    return dependenciesByShortId.mapNotNull { (shortId, dependency) ->
        var firstCoveredDependency: CoveredDependency? = null
        var exactCoveredDependency: CoveredDependency? = null
        var supersetClosureCoveredDependency: CoveredDependency? = null

        coveredByShortId[shortId].orEmpty().forEach { covered ->
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

/**
 * The base "is this dependency already covered" predicate all higher-level coverage rules in this
 * cluster build on: either the covering entry shares the same resolved owner identity
 * ([hasSameResolvedOwnerIdentityAs]), or it's a real (non-placeholder) direct dependency that
 * matches a declared-metadata placeholder's identity ([canCoverDeclaredPlaceholder]) - letting a
 * real resolution stand in for a placeholder that only carries user-declared metadata. The
 * direct/indirect asymmetry (`!dependency.direct || this.dependency.direct`) means a transitive
 * dependency can be covered by either a direct or transitive entry, but a direct (root) dependency
 * can only be covered by another direct entry - a transitive resolution is not strong enough
 * evidence that a root usage is redundant.
 */
internal fun CoveredDependency.canCover(dependency: ResolvedDependency): Boolean {
    return (this.dependency.hasSameResolvedOwnerIdentityAs(dependency) ||
        this.dependency.canCoverDeclaredPlaceholder(dependency)) &&
        (!dependency.direct || this.dependency.direct)
}

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
