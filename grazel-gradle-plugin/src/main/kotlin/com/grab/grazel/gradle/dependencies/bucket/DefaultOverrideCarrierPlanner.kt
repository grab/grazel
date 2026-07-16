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

import com.grab.grazel.gradle.dependencies.hasSameDefaultOwnerIdentityAs
import com.grab.grazel.gradle.dependencies.isDeclaredMetadata
import com.grab.grazel.gradle.dependencies.mavenOverrideTarget
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.versionInfo
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import java.util.stream.Collectors

internal class DefaultOverrideCarrierPlanner {

    /**
     * Builds, per non-default bucket, the list of dependencies that must still be explicitly
     * declared there rather than silently deferring to the default classpath - i.e. direct override
     * carriers (already flagged with an [ResolvedDependency.overrideTarget]) plus anything not
     * covered by the default flat classpath ([isCoveredByDefaultFlatClasspath]). A dependency that
     * *is* covered but should defer to a higher default version ([shouldUseDefaultVersion]) is
     * rewritten in place into a synthetic non-direct copy pointing at the default bucket via
     * [mavenOverrideTarget], so the generated build carries an explicit reference to the version
     * that actually wins instead of silently keeping its own (lower) version.
     */
    fun plan(
        flattenedClasspath: Map<String, Map<String, ResolvedDependency>>
    ): Map<String, List<ResolvedDependency>> {
        val defaultFlatClasspath = flattenedClasspath.getValue(DEFAULT_VARIANT)
        return reduceNonDefaultBuckets(flattenedClasspath) { default, bucket ->
            bucket.entries
                .parallelStream()
                .filter { (shortId, dependency) ->
                    val defaultDependency = default[shortId]
                    dependency.isDirectOverrideCarrier() ||
                        !dependency.isCoveredByDefaultFlatClasspath(defaultDependency)
                }
                .collect(
                    Collectors.toMap(
                        { (shortId, _) -> shortId },
                        { (shortId, dependency) ->
                            val defaultDependency = default[shortId]
                            if (defaultDependency != null && dependency.shouldUseDefaultVersion(defaultDependency)) {
                                defaultDependency.copy(
                                    direct = false,
                                    overrideTarget = mavenOverrideTarget(shortId, DEFAULT_VARIANT)
                                )
                            } else dependency
                        }
                    )
                )
        }.apply { put(DEFAULT_VARIANT, defaultFlatClasspath) }
            .mapValues { it.value.values.sortedBy(ResolvedDependency::id) }
    }

    /**
     * Splits into two disjoint checks, both requiring the [defaultDependency] to also be direct -
     * only a directly-declared default entry is strong enough evidence to make a direct entry here
     * redundant. A declared-metadata placeholder ([ResolvedDependency.isDeclaredMetadata]) can only
     * be covered by another direct declared placeholder with the same default owner identity, since
     * a placeholder carries no real resolution to match against a normal dependency. A normal
     * dependency instead requires the *default-direct* owner-identity match
     * ([hasSameDefaultDirectOwnerIdentityAs]), which is asymmetric from [CoveredDependency.canCover]
     * elsewhere in this cluster - override-carrier planning cares specifically about default's
     * direct declarations, not any arbitrary covering bucket.
     */
    private fun ResolvedDependency.isDirectDependencyCoveredBy(
        defaultDependency: ResolvedDependency?
    ): Boolean {
        if (isDeclaredMetadata()) {
            return direct &&
                defaultDependency?.direct == true &&
                defaultDependency.isDeclaredMetadata() &&
                defaultDependency.hasSameDefaultOwnerIdentityAs(this)
        }
        return direct &&
            defaultDependency?.direct == true &&
            defaultDependency.hasSameDefaultDirectOwnerIdentityAs(this)
    }

    /**
     * Ties [isDirectDependencyCoveredBy] together with a fallback for non-direct (transitive)
     * dependencies: even without a direct-coverage match, a transitive entry is still covered if it
     * is *not* one that [shouldUseDefaultVersion] (i.e. default doesn't hold a strictly newer
     * version worth deferring to) and it shares the same default-direct owner identity - meaning
     * default holds a matching entry (direct or not) at a version this entry is fine reusing as-is,
     * so no separate carrier entry is needed for it in this bucket.
     */
    private fun ResolvedDependency.isCoveredByDefaultFlatClasspath(
        defaultDependency: ResolvedDependency?
    ): Boolean {
        if (defaultDependency == null) return false
        if (isDirectDependencyCoveredBy(defaultDependency)) return true
        return !direct &&
            !shouldUseDefaultVersion(defaultDependency) &&
            defaultDependency.hasSameDefaultDirectOwnerIdentityAs(this)
    }

    private fun ResolvedDependency.isDirectOverrideCarrier(): Boolean {
        return direct && overrideTarget != null
    }

    private fun ResolvedDependency.shouldUseDefaultVersion(
        defaultDependency: ResolvedDependency
    ): Boolean {
        return !direct && defaultDependency.versionInfo > versionInfo
    }

    private fun ResolvedDependency.hasSameDefaultDirectOwnerIdentityAs(other: ResolvedDependency): Boolean {
        return shortId == other.shortId &&
            version == other.version &&
            excludeRules == other.excludeRules &&
            requiresJetifier == other.requiresJetifier
    }
}
