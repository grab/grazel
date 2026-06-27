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

import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import java.util.stream.Collectors

internal class DefaultBucketDependencyReducer {

    fun reduce(
        classPaths: Map<String, Map<String, ResolvedDependency>>
    ): Map<String, Map<String, ResolvedDependency>> {
        val defaultClasspath = classPaths.getValue(DEFAULT_VARIANT)
        return classPaths
            .entries
            .parallelStream()
            .filter { it.key != DEFAULT_VARIANT }
            .filter { it.value.isNotEmpty() }
            .collect(
                Collectors.toConcurrentMap({ it.key }, { (_, dependencies) ->
                    dependencies.entries
                        .parallelStream()
                        .filter { (shortId, dependency) ->
                            !defaultClasspath.containsDefaultOwnerEquivalent(shortId, dependency)
                        }
                        .collect(Collectors.toMap({ it.key }, { it.value }))
                })
            ).apply { put(DEFAULT_VARIANT, defaultClasspath) }
    }

    private fun Map<String, ResolvedDependency>.containsDefaultOwnerEquivalent(
        shortId: String,
        dependency: ResolvedDependency
    ): Boolean {
        val defaultDependency = this[shortId] ?: return false
        if (dependency.isDeclaredDependency() && !defaultDependency.isDeclaredDependency()) {
            return false
        }
        return defaultDependency.hasSameDefaultOwnerIdentityAs(dependency) &&
            (!dependency.direct || defaultDependency.direct)
    }

    private fun ResolvedDependency.isDeclaredDependency(): Boolean {
        return repository == DECLARED_DEPENDENCY_REPOSITORY
    }

    private fun ResolvedDependency.hasSameDefaultOwnerIdentityAs(other: ResolvedDependency): Boolean {
        return shortId == other.shortId &&
            version == other.version &&
            dependencies == other.dependencies &&
            excludeRules == other.excludeRules &&
            requiresJetifier == other.requiresJetifier
    }
}
