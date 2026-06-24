package com.grab.grazel.gradle.dependencies

import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.dependencies.model.allDependencies
import com.grab.grazel.gradle.dependencies.model.hasSameEffectiveIdentityAs
import com.grab.grazel.gradle.dependencies.model.merge
import com.grab.grazel.gradle.dependencies.model.versionInfo
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.util.KSP_MAVEN
import java.util.stream.Collector
import java.util.stream.Collectors

internal class ComputeWorkspaceDependencies {

    /**
     * Compute [WorkspaceDependencies] from already-resolved [ResolveDependenciesResult] instances.
     *
     * The results produced by [AggregatedDependencyResolver] are fed here directly. This performs
     * grouping, version arbitration, transitive flattening, override-target computation, and KSP
     * aggregation for generated workspace dependencies.
     *
     * @param results One [ResolveDependenciesResult] per synthetic bucket from
     *   [AggregatedDependencyResolver].
     */
    fun computeFromResults(results: List<ResolveDependenciesResult>): WorkspaceDependencies =
        computeInternal(results)

    /**
     * Accepts a materialized [List] of [ResolveDependenciesResult] — one per synthetic bucket —
     * and runs the 5-stage pipeline: group -> reduce -> flatten -> override-target ->
     * transitive-map, plus KSP aggregation.
     */
    private fun computeInternal(allResults: List<ResolveDependenciesResult>): WorkspaceDependencies {
        // Parse all jsons parallely and compute the classPaths among all variants.
        // Maximum compatible version is picked using [maxVersionReducer] since different buckets
        // can carry different resolved versions of the same dependency.
        val classPaths = allResults
            .parallelStream()
            .collect(
                Collectors.groupingByConcurrent(
                    ResolveDependenciesResult::variantName,
                    Collectors.flatMapping(
                        { resolvedDependency ->
                            resolvedDependency
                                .dependencies
                                .getValue(COMPILE.name)
                                .parallelStream()
                        },
                        Collectors.groupingByConcurrent(
                            ResolvedDependency::shortId,
                            maxVersionReducer()
                        )
                    )
                )
            )

        // A dependency may appear twice in both a specific bucket and `default`. In order to
        // correct this, we remove duplicates in non-default classPaths by comparing entries
        // against occurrences in the default classPath.
        val defaultClasspath = classPaths.getValue(DEFAULT_VARIANT)
        // Reduce non default classpath entries to contain only artifacts unique to them
        val reducedClasspath = classPaths
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

        // After reduction, flatten the dependency graph such that all transitive dependencies
        // appear as direct. Run the [maxVersionReducer] one more time to pick max version correctly
        val flattenClasspath = reducedClasspath
            .entries
            .parallelStream()
            .collect(
                Collectors.toConcurrentMap(
                    { it.key },
                    { (_, dependencyMap) ->
                        dependencyMap
                            .entries
                            .parallelStream()
                            .collect(
                                Collectors.flatMapping(
                                    // Flatten the transitive dependencies
                                    { it.value.allDependencies.stream() },
                                    Collectors.groupingByConcurrent(
                                        // Group by short id to ignore version in keys
                                        ResolvedDependency::shortId,
                                        // Once grouped, reduce it and only pick the highest version
                                        maxVersionReducer()
                                    )
                                )
                            )
                    })
            )

        // While the above map contains accurate version information in each classpath, there is
        // still possibility of duplicate versions among all classpath, in order to fix this
        // we iterate non default classpath once again but check if any of them appear already
        // in default classpath.
        // If they do establish a [OverrideTarget] to default classpath
        val defaultFlatClasspath = flattenClasspath.getValue(DEFAULT_VARIANT)

        val reducedFinalClasspath: Map<String, List<ResolvedDependency>> = flattenClasspath
            .entries
            .parallelStream()
            .filter { it.key != DEFAULT_VARIANT }
            .filter { it.value.isNotEmpty() }
            .collect(
                Collectors.toConcurrentMap(
                    { (shortId, _) -> shortId },
                    { (_, dependencies) ->
                        dependencies.entries
                            .parallelStream()
                            .filter { (shortId, dependency) ->
                                val defaultDependency = defaultFlatClasspath[shortId]
                                dependency.isDirectOverrideCarrier() ||
                                    !dependency.isCoveredByDefaultFlatClasspath(defaultDependency)
                            }
                            .collect(
                                Collectors.toMap(
                                    { (shortId, _) -> shortId },
                                    { (shortId, dependency) ->
                                        // If a transitive dependency is already in default classpath,
                                        // then we override it to point to default classpath instead
                                        val defaultDependency = defaultFlatClasspath[shortId]
                                        if (defaultDependency != null && dependency.shouldUseDefaultVersion(defaultDependency)) {
                                            defaultDependency.copy(
                                                direct = false,
                                                overrideTarget = mavenOverrideTarget(
                                                    shortId,
                                                    DEFAULT_VARIANT
                                                )
                                            )
                                        } else dependency
                                    })
                            )
                    })
            ).apply { put(DEFAULT_VARIANT, defaultFlatClasspath) }
            .mapValues { it.value.values.sortedBy(ResolvedDependency::id) }


        val variantTransitiveClasspath = reducedClasspath
            .mapValues { (_, dependencies) -> dependencies.values.transitiveClasspathByArtifactRoot() }
            .filterValues(Map<String, Set<String>>::isNotEmpty)

        // Preserve the global shortId index used by generated target transitive tags.
        val transitiveClasspath = variantTransitiveClasspath.globalTransitiveClasspath()
        val reachableMainBucketsByProject = allResults.reachableMainBucketsByProject()

        // Aggregate KSP deps across ALL variants into single bucket, deduplicated by max version
        val kspDeps: List<ResolvedDependency> = allResults
            .parallelStream()
            .flatMap { it.dependencies.getOrDefault(KSP.name, emptySet()).stream() }
            .collect(
                Collectors.groupingByConcurrent(
                    ResolvedDependency::shortId,
                    maxVersionReducer()
                )
            ).values.sortedBy(ResolvedDependency::id)

        // Clear maps to allow GC
        defaultFlatClasspath.clear()
        flattenClasspath.clear()
        reducedClasspath.clear()
        defaultClasspath.clear()
        classPaths.clear()
        return WorkspaceDependencies(
            variantDeps = reducedFinalClasspath,
            aggregatedRepos = if (kspDeps.isNotEmpty()) mapOf(KSP_MAVEN to kspDeps) else emptyMap(),
            transitiveClasspath = transitiveClasspath,
            variantTransitiveClasspath = variantTransitiveClasspath,
            reachableMainBucketsByProject = reachableMainBucketsByProject
        )
    }

    private fun List<ResolveDependenciesResult>.reachableMainBucketsByProject(): Map<String, Set<String>> =
        buildMap<String, MutableSet<String>> {
            this@reachableMainBucketsByProject.forEach { result ->
                result.reachableMainBucketsByProject.forEach { (projectPath, bucketNames) ->
                    getOrPut(projectPath) { sortedSetOf() }.addAll(bucketNames)
                }
            }
        }

    private fun Collection<ResolvedDependency>.transitiveClasspathByArtifactRoot(): Map<String, Set<String>> =
        asSequence()
            .filter { dependency -> dependency.dependencies.isNotEmpty() }
            .groupBy(ResolvedDependency::shortId)
            .mapValues { (_, dependencies) ->
                dependencies
                    .asSequence()
                    .flatMap { it.dependencies.asSequence() }
                    .map(ResolvedDependency::from)
                    .map(ResolvedDependency::shortId)
                    .toSortedSet()
            }
            .filterValues(Set<String>::isNotEmpty)

    private fun Map<String, Map<String, Set<String>>>.globalTransitiveClasspath(): Map<String, Set<String>> =
        buildMap<String, MutableSet<String>> {
            this@globalTransitiveClasspath.values.forEach { variantClasspath ->
                variantClasspath.forEach { (shortId, dependencies) ->
                    getOrPut(shortId) { sortedSetOf() }.addAll(dependencies)
                }
            }
        }

    /**
     * A reducing collector that picks the [ResolvedDependency] with higher
     * [ResolvedDependency.version] by simple comparison and merges metadata like exclude rules and
     * override targets.
     */
    private fun maxVersionReducer(): Collector<ResolvedDependency, *, ResolvedDependency> {
        return Collectors.reducing(null) { old, new ->
            when {
                old == null -> new
                new == null -> old
                // Pick the max version
                else -> if (old.versionInfo > new.versionInfo) old.merge(new) else new.merge(old)
            }
        }
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

    private fun ResolvedDependency.isDirectDependencyCoveredBy(
        defaultDependency: ResolvedDependency?
    ): Boolean {
        if (isDeclaredDependency()) {
            return direct &&
                defaultDependency?.direct == true &&
                defaultDependency.isDeclaredDependency() &&
                defaultDependency.hasSameDefaultOwnerIdentityAs(this)
        }
        return direct &&
            defaultDependency?.direct == true &&
            defaultDependency.hasSameDefaultDirectOwnerIdentityAs(this)
    }

    private fun ResolvedDependency.isCoveredByDefaultFlatClasspath(
        defaultDependency: ResolvedDependency?
    ): Boolean {
        if (defaultDependency == null) return false
        if (isDirectDependencyCoveredBy(defaultDependency)) return true
        return !direct &&
            !shouldUseDefaultVersion(defaultDependency) &&
            defaultDependency.hasSameDefaultDirectOwnerIdentityAs(this)
    }

    private fun ResolvedDependency.isDeclaredDependency(): Boolean {
        return repository == DECLARED_DEPENDENCY_REPOSITORY
    }

    private fun ResolvedDependency.isDirectOverrideCarrier(): Boolean {
        return direct && overrideTarget != null
    }

    private fun ResolvedDependency.shouldUseDefaultVersion(
        defaultDependency: ResolvedDependency
    ): Boolean {
        return !direct && defaultDependency.versionInfo > versionInfo
    }

    private fun ResolvedDependency.hasSameDefaultOwnerIdentityAs(other: ResolvedDependency): Boolean {
        return shortId == other.shortId &&
            version == other.version &&
            dependencies == other.dependencies &&
            excludeRules == other.excludeRules &&
            requiresJetifier == other.requiresJetifier
    }

    private fun ResolvedDependency.hasSameDefaultDirectOwnerIdentityAs(other: ResolvedDependency): Boolean {
        return shortId == other.shortId &&
            version == other.version &&
            excludeRules == other.excludeRules &&
            requiresJetifier == other.requiresJetifier
    }
}
