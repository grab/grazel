/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.gradle.dependencies.model

import com.grab.grazel.bazel.starlark.BazelDependency
import kotlinx.serialization.Serializable
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.Versioned
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.Version
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser

@Serializable
internal data class ResolveDependenciesResult(
    val variantName: String,
    val dependencies: Map<String, Set<ResolvedDependency>> = HashMap()
)

@Serializable
internal data class ResolvedDependency(
    val id: String,
    val version: String,
    val shortId: String,
    val direct: Boolean,
    val dependencies: Set<String>,
    val excludeRules: Set<ExcludeRule>,
    val repository: String,
    val overrideTarget: OverrideTarget? = null
) : Comparable<ResolvedDependency> {
    override fun compareTo(other: ResolvedDependency) = id.compareTo(other.id)

    companion object {
        fun from(dependencyNotation: String): ResolvedDependency {
            val (group, name, version, repository) = dependencyNotation.split(":")
            val shortId = "$group:$name"
            return ResolvedDependency(
                id = "$group:$name:$version",
                version = version,
                shortId = shortId,
                direct = false,
                dependencies = emptySet(),
                excludeRules = emptySet(),
                repository = repository
            )
        }
    }
}

internal fun ResolvedDependency.merge(other: ResolvedDependency): ResolvedDependency {
    return copy(
        direct = direct || other.direct,
        excludeRules = (excludeRules + other.excludeRules)
            .toList()
            .toSortedSet(compareBy(ExcludeRule::toString)),
    )
}

@Serializable
internal data class OverrideTarget(
    val artifactShortId: String,
    val label: BazelDependency.MavenDependency,
)

@Serializable
internal data class WorkspaceDependencies(
    val result: Map<String, List<ResolvedDependency>>
)

/**
 * Unwrap [ResolvedDependency] such that it contains all its dependencies in the form of
 * [ResolvedDependency]
 */
internal val ResolvedDependency.allDependencies: Set<ResolvedDependency>
    get() = buildSet {
        add(this@allDependencies.copy(dependencies = emptySet()))
        addAll(dependencies.map { dependency -> ResolvedDependency.from(dependency) })
    }

/**
 * Proxy class to use [Versioned] so that we can use [DefaultVersionComparator]
 */
internal class VersionInfo(val version: String) : Versioned, Comparable<VersionInfo> {
    private val parsedVersion = VersionParser().transform(version)
    override fun getVersion(): Version = parsedVersion
    private val comparator = DefaultVersionComparator()
    override fun compareTo(other: VersionInfo) = comparator.compare(this, other)
}

internal val ResolvedDependency.versionInfo get() = VersionInfo(version = version)