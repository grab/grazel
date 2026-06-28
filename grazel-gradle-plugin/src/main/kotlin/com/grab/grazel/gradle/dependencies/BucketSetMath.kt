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

internal fun Map<String, ResolvedDependency>.withoutDependenciesCoveredBy(
    coveredDependencies: Iterable<CoveredDependency>
): Map<String, ResolvedDependency> =
    withoutDependenciesCoveredBy(coveredDependencies.groupByShortId())

internal fun Iterable<CoveredDependency>.groupByShortId(): Map<String, List<CoveredDependency>> =
    groupBy { it.dependency.shortId }

internal fun Map<String, ResolvedDependency>.withoutDependenciesCoveredBy(
    coveredByShortId: Map<String, List<CoveredDependency>>
): Map<String, ResolvedDependency> {
    return mapNotNull { (shortId, dependency) ->
        val coveringDependencies = coveredByShortId[shortId]
            .orEmpty()
            .filter { covered -> covered.canCover(dependency) }
        val exactCoveredDependency = coveringDependencies.firstOrNull { covered ->
            covered.dependency.hasSameResolvedArtifactIdentityAs(dependency)
        }
        val supersetClosureCoveredDependency = coveringDependencies.firstOrNull { covered ->
            covered.rootsSupersetClosureOf(dependency)
        }
        val coveredDependency = exactCoveredDependency
            ?: supersetClosureCoveredDependency
            ?: coveringDependencies.firstOrNull()
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

internal fun Map<String, ResolvedDependency>.withoutDependenciesOwnedByNonDefaultHierarchy(
    hierarchyDefaultDeps: Map<String, ResolvedDependency>,
    nonDefaultHierarchyDependencies: Iterable<ResolvedDependency>
): Map<String, ResolvedDependency> {
    val nonDefaultByShortId = nonDefaultHierarchyDependencies.groupBy { it.shortId }
    return filterValues { dependency ->
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
    val intersectionIds = closureList
        .map { it.keys }
        .reduce { a, b -> a intersect b }
    val firstClosure = closureList.first()
    return intersectionIds.mapNotNull { id ->
        val candidate = firstClosure[id]!!
        if (closureList.all { closure -> closure[id]?.hasSameResolvedOwnerIdentityAs(candidate) == true }) {
            id to candidate
        } else {
            null
        }
    }.toMap()
}

internal fun Map<String, ResolvedDependency>.asCoveredBy(bucketName: String): List<CoveredDependency> {
    return values.map { dependency -> CoveredDependency(bucketName, dependency) }
}

private fun CoveredDependency.toOverrideTarget(): OverrideTarget {
    return mavenOverrideTarget(dependency.shortId, bucketName)
}
