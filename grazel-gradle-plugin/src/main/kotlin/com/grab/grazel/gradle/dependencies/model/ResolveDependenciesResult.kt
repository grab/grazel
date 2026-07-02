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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.Versioned
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.DefaultVersionComparator
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.Version
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionParser
import org.gradle.api.internal.artifacts.result.DefaultResolvedComponentResult

@Serializable
internal data class ResolveDependenciesResult(
    val variantName: String,
    val dependencies: Map<String, Set<ResolvedDependency>> = HashMap(),
    val reachableMainBucketsByProject: Map<String, Set<String>> = emptyMap()
) {
    companion object {
        enum class Scope {
            COMPILE,
            KSP
        }
    }
}

@Serializable
internal data class ResolvedDependency(
    val id: String,
    val version: String,
    val shortId: String,
    val direct: Boolean,
    val dependencies: Set<String>,
    val excludeRules: Set<ExcludeRule>,
    val repository: String,
    val requiresJetifier: Boolean,
    val jetifierSource: String? = null,
    val overrideTarget: OverrideTarget? = null,
    val processorClass: String? = null  // For KSP processors
) : Comparable<ResolvedDependency> {
    override fun compareTo(other: ResolvedDependency) = id.compareTo(other.id)

    companion object {
        /**
         * Construct [ResolvedDependency] from id alone
         */
        fun fromId(id: String, repository: String): ResolvedDependency {
            val notation = ResolvedDependencyNotation.fromId(id)
            return ResolvedDependency(
                id = id,
                version = notation.version,
                shortId = notation.shortId,
                direct = true,
                dependencies = emptySet(),
                excludeRules = emptySet(),
                repository = repository,
                requiresJetifier = false,
                jetifierSource = null
            )
        }

        fun from(dependencyNotation: String): ResolvedDependency {
            val notation = ResolvedDependencyNotation.parse(dependencyNotation)
            return ResolvedDependency(
                id = notation.id,
                version = notation.version,
                shortId = notation.shortId,
                direct = false,
                dependencies = emptySet(),
                excludeRules = emptySet(),
                repository = notation.repository,
                requiresJetifier = notation.requiresJetifier,
                jetifierSource = notation.jetifierSource
            )
        }

        fun createDependencyNotation(
            component: ResolvedComponentResult,
            requiresJetifier: Boolean,
            jetifierSource: String?
        ): String {
            val repository = (component as? DefaultResolvedComponentResult)?.repositoryName ?: ""
            return "$component:${repository}:$requiresJetifier:$jetifierSource"
        }
    }
}

private data class ResolvedDependencyNotation(
    val group: String,
    val name: String,
    val version: String,
    val repository: String = "",
    val requiresJetifier: Boolean = false,
    val jetifierSource: String? = null
) {
    val id: String get() = "$group:$name:$version"
    val shortId: String get() = "$group:$name"

    companion object {
        fun fromId(id: String): ResolvedDependencyNotation {
            val parts = id.split(":")
            require(parts.size == 3) { "Expected dependency id as group:name:version, got '$id'" }
            return ResolvedDependencyNotation(
                group = parts[0],
                name = parts[1],
                version = parts[2]
            )
        }

        fun parse(dependencyNotation: String): ResolvedDependencyNotation {
            val parts = dependencyNotation.split(":")
            require(parts.size >= 6) {
                "Expected dependency notation as group:name:version:repository:requiresJetifier:jetifierSource, got '$dependencyNotation'"
            }
            val jetifierSource = parts.drop(5)
                .joinToString(":")
                .takeUnless { source -> source == "null" }
            return ResolvedDependencyNotation(
                group = parts[0],
                name = parts[1],
                version = parts[2],
                repository = parts[3],
                requiresJetifier = parts[4].toBoolean(),
                jetifierSource = jetifierSource
            )
        }
    }
}

internal fun ResolvedDependency.merge(other: ResolvedDependency): ResolvedDependency {
    return copy(
        direct = direct || other.direct,
        dependencies = (dependencies + other.dependencies).toSortedSet(),
        jetifierSource = jetifierSource ?: other.jetifierSource,
        overrideTarget = overrideTarget ?: other.overrideTarget,
        excludeRules = excludeRules.intersectWith(other.excludeRules),
        processorClass = processorClass ?: other.processorClass,
    )
}

internal fun Set<ExcludeRule>.intersectWith(other: Set<ExcludeRule>): Set<ExcludeRule> =
    when {
        isEmpty() || other.isEmpty() -> emptySet()
        else -> intersect(other).toSortedSet(compareBy(ExcludeRule::toString))
    }

internal fun ResolvedDependency.hasSameResolvedArtifactIdentityAs(other: ResolvedDependency): Boolean {
    return shortId == other.shortId &&
        version == other.version &&
        dependencies == other.dependencies &&
        excludeRules == other.excludeRules &&
        repository == other.repository &&
        requiresJetifier == other.requiresJetifier &&
        jetifierSource == other.jetifierSource
}

internal fun ResolvedDependency.hasSameResolvedOwnerIdentityAs(other: ResolvedDependency): Boolean {
    return shortId == other.shortId &&
        version == other.version &&
        excludeRules == other.excludeRules &&
        repository == other.repository &&
        requiresJetifier == other.requiresJetifier &&
        jetifierSource == other.jetifierSource
}

@Serializable
internal data class OverrideTarget(
    val artifactShortId: String,
    val label: BazelDependency.MavenDependency,
)

@Serializable
internal data class WorkspaceDependencies(
    /**
     * Compile/runtime dependencies grouped by Gradle variant name (e.g. "default", "demoDebug").
     * Each entry maps 1:1 to a `maven_install` repository via [toMavenRepoName].
     * Keys are variant names, not repo names.
     */
    @SerialName("result")
    val variantDeps: Map<String, List<ResolvedDependency>>,
    /**
     * Dependencies aggregated across all variants into a single repo, keyed directly by
     * maven repo name (e.g. "ksp_maven"). Unlike [variantDeps], these do not go through
     * the variant deduplication/reduction pipeline.
    */
    val aggregatedRepos: Map<String, List<ResolvedDependency>> = emptyMap(),
    val transitiveClasspath: Map<String, Set<String>> = emptyMap(),
    /**
     * Variant-scoped view of [transitiveClasspath].
     *
     * The global transitive dependency store is keyed by artifact shortId. Generated targets decide
     * which declared roots to query, so this map keeps the resolved closure for any artifact root
     * that carries transitive metadata in a scoped bucket.
     */
    val variantTransitiveClasspath: Map<String, Map<String, Set<String>>> = emptyMap(),
    /**
     * Main-variant project buckets reachable from the selected app/com.android.test roots.
     *
     * Generated BUILD files can still exist for inactive modules, but dependency lookup should only
     * fail hard for missing Maven labels when the module/bucket participates in the selected root
     * graph. This keeps root-selected workspace buckets strict without requiring full per-module
     * resolution for inactive modules.
     */
    val reachableMainBucketsByProject: Map<String, Set<String>> = emptyMap()
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
internal class ComparableGradleVersion(val version: String) : Versioned, Comparable<ComparableGradleVersion> {
    private val parsedVersion = VersionParser().transform(version)
    override fun getVersion(): Version = parsedVersion
    private val comparator = DefaultVersionComparator()
    override fun compareTo(other: ComparableGradleVersion) = comparator.compare(this, other)
}

internal val ResolvedDependency.versionInfo get() = ComparableGradleVersion(version = version)
