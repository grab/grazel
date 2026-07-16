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

import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.ProjectDependencyBucket
import com.grab.grazel.gradle.dependencies.isDeclaredMetadata
import com.grab.grazel.gradle.dependencies.mergeDependencyMetadataByMaxVersion
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.util.merge

internal data class MainBucketPlanResult(
    val defaultDeps: Map<String, ResolvedDependency>,
    val hierarchyBuckets: Map<String, Map<String, ResolvedDependency>>,
    val filteredPerLeafBuckets: Map<String, Map<String, ResolvedDependency>>,
    val mainCoveredDepsByProject: Map<String, List<CoveredDependency>>
) {
    fun coveredDependencies(): List<CoveredDependency> =
        allCoveredDependencies(DEFAULT_VARIANT, defaultDeps, hierarchyBuckets, filteredPerLeafBuckets)
}

/**
 * Plans main (non-test) bucket ownership for every project: default classpath, hierarchy buckets
 * (flavor/buildType), and leaf variants. Delegates per-project placement to
 * [DependencyBucketPlacementEngine], then merges the independently-placed plans into
 * project-agnostic output buckets and reconciles user-declared dependency metadata (excludes,
 * overrides) back onto the placed buckets.
 */
internal class MainBucketPlanner(
    private val declaredDependencyMetadata: DeclaredDependencyMetadata
) {
    /**
     * Merges each project's independently-placed main bucket plan ([DependencyBucketPlacementPlan])
     * into project-agnostic default/hierarchy/leaf bucket maps, then layers in two corrections
     * that only make sense once results from all projects are combined:
     *
     * 1. User-declared metadata (excludes, overrides, ...) is applied per output bucket rather than
     *    per project, since a dependency declared in one project's bucket must also carry that
     *    metadata for every other project sharing the same output bucket name.
     * 2. Declared-metadata placeholders that survive placement in a hierarchy/leaf bucket are
     *    stripped via [withoutDeclaredPlaceholdersCoveredByDefault] whenever the merged default
     *    bucket already covers the same dependency - otherwise the placeholder would duplicate an
     *    entry that already exists (and is authoritative) in default.
     *
     * Leaf buckets go through an extra pass ([withGlobalAncestorResolvedMetadata]) to backfill
     * metadata from every ancestor hierarchy bucket - not just the ones from the leaf's own
     * project - because ancestor bucket names are shared across projects but resolved metadata is
     * computed per project.
     */
    fun plan(input: OwnershipPlannerInput): MainBucketPlanResult {
        val mainBucketVariants = declaredDependencyMetadata.mainBucketVariantsByProject()
        val declaredMainDependenciesByBucket = declaredDependencyMetadata
            .collectDeclaredMainDependenciesByProjectBucket(declaredDependencyMetadata.projects.keys)
        val mainBucketPlansByProject = DependencyBucketPlacementEngine().planByProject(
            variants = mainBucketVariants,
            hierarchyBucketClosures = input.hierarchyBucketClosures,
            leafClosures = input.leafClosures
        ).mapValues { (projectPath, plan) ->
            withDeclaredMainMetadata(
                plan = plan,
                projectPath = projectPath,
                declaredMainDependenciesByBucket = declaredMainDependenciesByBucket
            )
        }
        val mainBucketPlans = mainBucketPlansByProject.values
        val declaredMetadataByOutputBucket = linkedMapOf<String, Map<String, ResolvedDependency>>()
        mainBucketPlansByProject.forEach { (projectPath, plan) ->
            addDeclaredOutputMetadata(
                declaredMetadataByOutputBucket = declaredMetadataByOutputBucket,
                bucketName = plan.baseBucketName,
                metadata = declaredOutputMetadata(
                    projectPath = projectPath,
                    bucketName = plan.baseBucketName,
                    dependencies = plan.defaultBucket,
                    declaredDependenciesByBucket = declaredMainDependenciesByBucket
                )
            )
            plan.hierarchyBuckets.forEach { (bucketName, dependencies) ->
                addDeclaredOutputMetadata(
                    declaredMetadataByOutputBucket = declaredMetadataByOutputBucket,
                    bucketName = bucketName,
                    metadata = declaredOutputMetadata(
                        projectPath = projectPath,
                        bucketName = bucketName,
                        dependencies = dependencies,
                        declaredDependenciesByBucket = declaredMainDependenciesByBucket
                    )
                )
            }
            plan.leafBuckets.forEach { (bucketName, dependencies) ->
                addDeclaredOutputMetadata(
                    declaredMetadataByOutputBucket = declaredMetadataByOutputBucket,
                    bucketName = bucketName,
                    metadata = declaredOutputMetadata(
                        projectPath = projectPath,
                        bucketName = bucketName,
                        dependencies = dependencies,
                        declaredDependenciesByBucket = declaredMainDependenciesByBucket
                    )
                )
            }
        }
        val defaultDeps = applyDeclaredMetadata(
            dependencies = mainBucketPlans
                .map(DependencyBucketPlacementPlan::defaultBucket)
                .merge(::mergeDependencyMetadataByMaxVersion),
            declaredDependencies = declaredMetadataByOutputBucket[DEFAULT_VARIANT].orEmpty()
        )
        val hierarchyBuckets = withoutDeclaredPlaceholdersCoveredByDefault(
            dependenciesByBucket = applyDeclaredMetadataByBucket(
                dependenciesByBucket = mergeNamedBuckets(
                    mainBucketPlans.map(DependencyBucketPlacementPlan::hierarchyBuckets)
                ),
                declaredMetadataByOutputBucket = declaredMetadataByOutputBucket
            ),
            defaultDeps = defaultDeps
        )
        val mainCoveredDepsByProject = mainBucketPlansByProject
            .mapValues { (_, plan) -> plan.coveredDependencies() }
        val leafAncestorsByName = linkedMapOf<String, MutableSet<String>>()
        mainBucketPlans.forEach { plan ->
            plan.leafAncestors.forEach { (leafName, ancestors) ->
                leafAncestorsByName
                    .getOrPut(leafName) { sortedSetOf() }
                    .addAll(ancestors)
            }
        }
        val filteredPerLeafBuckets = withGlobalAncestorResolvedMetadata(
            leafBuckets = mergeNamedBuckets(
                mainBucketPlans.map { plan ->
                    plan.leafBuckets
                        .mapValues { (leafName, dependencies) ->
                            Coverage.of(
                                plan.leafAncestors[leafName]
                                    .orEmpty()
                                    .flatMap { ancestorName ->
                                        coveredDependenciesForBucket(
                                            dependenciesByShortId = hierarchyBuckets[ancestorName].orEmpty(),
                                            bucketName = ancestorName
                                        )
                                    }
                            ).subtract(dependencies)
                        }
                        .filterValues(Map<String, ResolvedDependency>::isNotEmpty)
                }
            ),
            leafAncestorsByName = leafAncestorsByName,
            hierarchyBuckets = hierarchyBuckets
        ).let { dependenciesByBucket ->
            withoutDeclaredPlaceholdersCoveredByDefault(
                dependenciesByBucket = applyDeclaredMetadataByBucket(
                    dependenciesByBucket = dependenciesByBucket,
                    declaredMetadataByOutputBucket = declaredMetadataByOutputBucket
                ),
                defaultDeps = defaultDeps
            )
        }
        return MainBucketPlanResult(
            defaultDeps = defaultDeps,
            hierarchyBuckets = hierarchyBuckets,
            filteredPerLeafBuckets = filteredPerLeafBuckets,
            mainCoveredDepsByProject = mainCoveredDepsByProject
        )
    }

    private fun withDeclaredMainMetadata(
        plan: DependencyBucketPlacementPlan,
        projectPath: String,
        declaredMainDependenciesByBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
    ): DependencyBucketPlacementPlan {
        return plan.copy(
            defaultBucket = applyDeclaredMetadata(
                dependencies = plan.defaultBucket,
                declaredDependencies = declaredOutputMetadata(
                    projectPath = projectPath,
                    bucketName = plan.baseBucketName,
                    dependencies = plan.defaultBucket,
                    declaredDependenciesByBucket = declaredMainDependenciesByBucket
                )
            ),
            hierarchyBuckets = plan.hierarchyBuckets
                .mapValues { (bucketName, dependencies) ->
                    applyDeclaredMetadata(
                        dependencies = dependencies,
                        declaredDependencies = declaredOutputMetadata(
                            projectPath = projectPath,
                            bucketName = bucketName,
                            dependencies = dependencies,
                            declaredDependenciesByBucket = declaredMainDependenciesByBucket
                        )
                    )
                }
                .toSortedMap(),
            leafBuckets = plan.leafBuckets
                .mapValues { (bucketName, dependencies) ->
                    applyDeclaredMetadata(
                        dependencies = dependencies,
                        declaredDependencies = declaredOutputMetadata(
                            projectPath = projectPath,
                            bucketName = bucketName,
                            dependencies = dependencies,
                            declaredDependenciesByBucket = declaredMainDependenciesByBucket
                        )
                    )
                }
                .toSortedMap()
        )
    }

    private fun declaredOutputMetadata(
        projectPath: String,
        bucketName: String,
        dependencies: Map<String, ResolvedDependency>,
        declaredDependenciesByBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
    ): Map<String, ResolvedDependency> {
        if (dependencies.isEmpty()) return emptyMap()
        return declaredDependenciesByBucket[ProjectDependencyBucket(projectPath, bucketName)]
            .orEmpty()
            .filterKeys { shortId -> shortId in dependencies }
    }

    private fun mergeNamedBuckets(
        bucketsByPlan: Iterable<Map<String, Map<String, ResolvedDependency>>>
    ): Map<String, Map<String, ResolvedDependency>> {
        val mergedBuckets = linkedMapOf<String, Map<String, ResolvedDependency>>()
        bucketsByPlan.forEach { buckets ->
            buckets.toSortedMap().forEach { (bucketName, dependencies) ->
                mergedBuckets.mergeBucket(bucketName, dependencies)
            }
        }
        return mergedBuckets.toSortedMap()
    }

    /**
     * A leaf bucket's own [DependencyBucketPlacementPlan] only resolves metadata against ancestor
     * hierarchy buckets from the *same project*. But hierarchy bucket names (e.g. a flavor name)
     * are shared across projects and get merged in [plan], so an ancestor bucket can carry metadata
     * (e.g. a higher version) contributed by a different project than the leaf's own. This pass
     * re-merges each leaf dependency against every one of its ancestors' *merged* versions so leaf
     * metadata reflects the global, cross-project view rather than a stale per-project snapshot.
     */
    private fun withGlobalAncestorResolvedMetadata(
        leafBuckets: Map<String, Map<String, ResolvedDependency>>,
        leafAncestorsByName: Map<String, Set<String>>,
        hierarchyBuckets: Map<String, Map<String, ResolvedDependency>>
    ): Map<String, Map<String, ResolvedDependency>> {
        return leafBuckets.mapValues { (leafName, dependencies) ->
            val ancestorDependenciesByShortId = linkedMapOf<String, ResolvedDependency>()
            leafAncestorsByName[leafName]
                .orEmpty()
                .forEach { ancestorName ->
                    hierarchyBuckets[ancestorName]
                        .orEmpty()
                        .values
                        .forEach { dependency ->
                            ancestorDependenciesByShortId.merge(
                                dependency.shortId,
                                dependency,
                                ::mergeDependencyMetadataByMaxVersion
                            )
                        }
                }
            if (ancestorDependenciesByShortId.isEmpty()) {
                dependencies
            } else {
                dependencies.mapValues { (shortId, dependency) ->
                    ancestorDependenciesByShortId[shortId]
                        ?.let { ancestorDependency ->
                            mergeDependencyMetadataByMaxVersion(dependency, ancestorDependency)
                        }
                        ?: dependency
                }.toSortedMap()
            }
        }.toSortedMap()
    }
}

/**
 * Declared-metadata placeholders (entries carrying only user-declared overrides/excludes, no real
 * artifact) can survive placement into a non-default bucket even when the same dependency is
 * already present in the merged default bucket - since default is merged from all projects only
 * after per-project placement runs. Left alone, this would emit a redundant declared entry in a
 * downstream bucket for a dependency default already owns. This strips exactly those placeholders,
 * using the *default* bucket's coverage ([CoveredDependency.canCover]) as the source of truth,
 * without touching any non-placeholder (real) dependency.
 */
private fun withoutDeclaredPlaceholdersCoveredByDefault(
    dependenciesByBucket: Map<String, Map<String, ResolvedDependency>>,
    defaultDeps: Map<String, ResolvedDependency>
): Map<String, Map<String, ResolvedDependency>> {
    if (dependenciesByBucket.isEmpty() || defaultDeps.isEmpty()) return dependenciesByBucket
    val defaultCoveredDepsByShortId = coveredDependenciesForBucket(defaultDeps, DEFAULT_VARIANT)
        .let(::groupCoveredDependenciesByShortId)
    return dependenciesByBucket.mapValues { (_, dependencies) ->
        dependencies
            .filterNot { (_, dependency) ->
                dependency.isDeclaredMetadata() &&
                    defaultCoveredDepsByShortId[dependency.shortId]
                        .orEmpty()
                        .any { covered -> covered.canCover(dependency) }
            }
            .toSortedMap()
    }
        .filterValues(Map<String, ResolvedDependency>::isNotEmpty)
        .toSortedMap()
}
