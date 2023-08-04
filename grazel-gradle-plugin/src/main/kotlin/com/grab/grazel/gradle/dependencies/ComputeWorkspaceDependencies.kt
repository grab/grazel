package com.grab.grazel.gradle.dependencies

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.gradle.dependencies.model.OverrideTarget
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.dependencies.model.allDependencies
import com.grab.grazel.gradle.dependencies.model.merge
import com.grab.grazel.gradle.dependencies.model.versionInfo
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.util.fromJson
import org.gradle.api.file.RegularFile
import java.util.stream.Collector
import java.util.stream.Collectors


internal class ComputeWorkspaceDependencies {

    fun compute(compileDependenciesJsons: List<RegularFile>): WorkspaceDependencies {
        // Parse all jsons parallely and compute the classPaths among all variants.
        // Maximum compatible version is picked using [maxVersionReducer] since jsons produced by
        // [ResolveVariantDependenciesTask] is module specific and we can have two version of the
        // same dependency.
        val classPaths = compileDependenciesJsons
            .parallelStream()
            .map<ResolveDependenciesResult>(::fromJson)
            .collect(
                Collectors.groupingByConcurrent(
                    ResolveDependenciesResult::variantName,
                    Collectors.flatMapping(
                        { resolvedDependency ->
                            resolvedDependency
                                .dependencies
                                .getValue("compile")
                                .parallelStream()
                        },
                        Collectors.groupingByConcurrent(
                            ResolvedDependency::shortId,
                            maxVersionReducer()
                        )
                    )
                )
            )

        // Even though [ResolveVariantDependenciesTask] does classpath reduction per module, the
        // final classpath here will not be accurate. For example, a dependency may appear twice in
        // both `release` and `default`. In order to correct this, we remove duplicates in non default
        // classPaths by comparing entries against occurrence in default classPath.
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
                        .filter { it.key !in defaultClasspath }
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
                            .collect(
                                Collectors.toMap(
                                    { (shortId, _) -> shortId },
                                    { (shortId, dependency) ->
                                        // If a transitive dependency is already in default classpath,
                                        // then we override it to point to default classpath instead
                                        if (shortId in defaultFlatClasspath && !dependency.direct) {
                                            val (group, name, _, _) = dependency!!.id.split(":")
                                            dependency.copy(
                                                overrideTarget = OverrideTarget(
                                                    artifactShortId = shortId,
                                                    label = BazelDependency.MavenDependency(
                                                        group = group,
                                                        name = name
                                                    )
                                                )
                                            )
                                        } else dependency
                                    })
                            )
                    })
            ).apply { put(DEFAULT_VARIANT, defaultFlatClasspath) }
            .mapValues { it.value.values.sortedBy(ResolvedDependency::id) }

        return WorkspaceDependencies(result = reducedFinalClasspath)
    }

    /**
     * A reducing collector that picks the [ResolvedDependency] with higher [ResolvedDependency.version]
     * by simple comparison and merges metadata like exclude rules and override targets.
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
}