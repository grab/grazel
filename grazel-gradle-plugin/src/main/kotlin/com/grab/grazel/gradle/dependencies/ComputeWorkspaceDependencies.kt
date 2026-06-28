package com.grab.grazel.gradle.dependencies

import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.dependencies.model.allDependencies
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
    fun computeFromResults(results: List<ResolveDependenciesResult>): WorkspaceDependencies {
        // Group already-resolved bucket results by classpath and short id.
        // Maximum compatible version is picked using [maxVersionReducer] because different
        // buckets can carry different resolved versions of the same dependency.
        val classPaths = results
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

        val reducedClasspath = DefaultBucketDependencyReducer().reduce(classPaths)

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
                                        flattenedClasspathReducer()
                                    )
                                )
                            )
                    })
            )

        val reducedFinalClasspath = DefaultOverrideCarrierPlanner().plan(flattenClasspath)


        val variantTransitiveClasspath = reducedClasspath
            .mapValues { (_, dependencies) -> dependencies.values.transitiveClasspathByArtifactRoot() }
            .filterValues(Map<String, Set<String>>::isNotEmpty)

        // Preserve the global shortId index used by generated target transitive tags.
        val transitiveClasspath = variantTransitiveClasspath.globalTransitiveClasspath()
        val reachableMainBucketsByProject = results.reachableMainBucketsByProject()

        // Aggregate KSP deps across ALL variants into single bucket, deduplicated by max version
        val kspDeps: List<ResolvedDependency> = results
            .parallelStream()
            .flatMap { it.dependencies.getOrDefault(KSP.name, emptySet()).stream() }
            .collect(
                Collectors.groupingByConcurrent(
                    ResolvedDependency::shortId,
                    maxVersionReducer()
                )
            ).values.sortedBy(ResolvedDependency::id)

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
                else -> mergeDependencyMetadataByMaxVersion(old, new)
            }
        }
    }

    private fun flattenedClasspathReducer(): Collector<ResolvedDependency, *, ResolvedDependency> {
        return Collectors.reducing(null) { old, new ->
            when {
                old == null -> new
                new == null -> old
                else -> mergeFlattenedDependencyMetadata(old, new)
            }
        }
    }

    private fun mergeFlattenedDependencyMetadata(
        old: ResolvedDependency,
        new: ResolvedDependency
    ): ResolvedDependency {
        val merged = mergeDependencyMetadataByMaxVersion(old, new)
        val directDependency = when {
            old.direct && !new.direct -> old
            new.direct && !old.direct -> new
            else -> null
        }
        return if (directDependency != null && directDependency.version == merged.version) {
            merged.copy(
                excludeRules = (merged.excludeRules + directDependency.excludeRules)
                    .toSortedSet(compareBy { rule -> rule.toString() })
            )
        } else {
            merged
        }
    }

}
