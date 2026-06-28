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

private fun CoveredDependency.toOverrideTarget(): OverrideTarget {
    return mavenOverrideTarget(dependency.shortId, bucketName)
}
