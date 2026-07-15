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

import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.LINT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.Test
import com.grab.grazel.util.merge

internal data class OwnershipPlannerInput(
    val leafClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
    val leafUnitTestClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
    val leafAndroidTestClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
    val hierarchyBucketClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
    val testHierarchyBucketClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
    val reachableMainBucketNamesByProject: Map<String, Set<String>>,
    val lintDeps: Map<String, ResolvedDependency>,
    val declaredTestDependenciesByBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
)

private data class MainBucketPlanResult(
    val defaultDeps: Map<String, ResolvedDependency>,
    val hierarchyBuckets: Map<String, Map<String, ResolvedDependency>>,
    val filteredPerLeafBuckets: Map<String, Map<String, ResolvedDependency>>,
    val mainCoveredDepsByProject: Map<String, List<CoveredDependency>>
) {
    fun coveredDependencies(): List<CoveredDependency> =
        allCoveredDependencies(DEFAULT_VARIANT, defaultDeps, hierarchyBuckets, filteredPerLeafBuckets)
}

/**
 * Decides, for every Gradle variant "bucket" (default classpath, hierarchy bucket such as a
 * flavor/buildType, or leaf variant) of a project, which resolved dependencies that bucket should
 * own in the generated Bazel target graph - i.e. it turns a flat, per-variant resolution result
 * into a deduplicated ownership plan where each dependency is declared exactly once, as high up
 * the variant hierarchy as its actual usage allows, and test source sets only carry what main
 * hasn't already covered. This sits between dependency resolution and Bazel target rendering: the
 * output buckets become individual `maven_install`/target dependency lists.
 *
 * Main bucket placement is delegated to [DependencyBucketPlacementEngine]; this class layers test
 * bucket planning (unit test / android test) and cross-bucket coverage subtraction on top, plus
 * reconciliation of user-declared dependency metadata (excludes, overrides) back onto the placed
 * buckets.
 */
internal class BucketOwnershipPlanner(
    private val declaredDependencyMetadata: DeclaredDependencyMetadata,
    private val precomputedKspDependencies: Set<ResolvedDependency>
) {
    /**
     * Orchestrates the three-stage plan: main buckets first (default/hierarchy/leaf), then unit
     * test buckets - which may skip any dependency already covered by main - then android test
     * buckets, which additionally skip anything unit test buckets already cover. This ordering is
     * an invariant: android test coverage subtraction depends on the already-computed unit test
     * result ([testBuckets]), so unit test buckets must be planned first.
     */
    fun plan(input: OwnershipPlannerInput): List<ResolveDependenciesResult> {
        val mainBuckets = planMainBuckets(input)
        val aggregateMainCoveredDeps = mainBuckets.coveredDependencies()
        val testBucketPlanner = TestBucketPlanner(declaredDependencyMetadata)
        val testBuckets = testBucketPlanner.plan(
            input = input,
            variantType = Test,
            baseBucketName = TEST_VARIANT,
            leafClosures = input.leafUnitTestClosures,
            mainCoveredDepsByProject = mainBuckets.mainCoveredDepsByProject,
            aggregateMainCoveredDeps = aggregateMainCoveredDeps,
            declaredTestDependenciesByBucket = input.declaredTestDependenciesByBucket
        )
        val androidTestBuckets = testBucketPlanner.plan(
            input = input,
            variantType = AndroidTest,
            baseBucketName = ANDROID_TEST_VARIANT,
            leafClosures = input.leafAndroidTestClosures,
            mainCoveredDepsByProject = mainBuckets.mainCoveredDepsByProject,
            aggregateMainCoveredDeps = aggregateMainCoveredDeps,
            declaredTestDependenciesByBucket = input.declaredTestDependenciesByBucket,
            inheritedTestCoveredDeps = testBuckets
                .flatMap { (bucketName, deps) -> coveredDependenciesForBucket(deps, bucketName) }
        )

        return buildResults(
            input = input,
            mainBuckets = mainBuckets,
            testBuckets = testBuckets,
            androidTestBuckets = androidTestBuckets
        )
    }

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
    private fun planMainBuckets(input: OwnershipPlannerInput): MainBucketPlanResult {
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
            dependencies = mergeDependencyMaps(
                mainBucketPlans.map(DependencyBucketPlacementPlan::defaultBucket)
            ),
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

    private fun mergeDependencyMaps(
        dependencyMaps: Iterable<Map<String, ResolvedDependency>>
    ): Map<String, ResolvedDependency> {
        return dependencyMaps.merge(::mergeDependencyMetadataByMaxVersion)
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
     * are shared across projects and get merged in [planMainBuckets], so an ancestor bucket can
     * carry metadata (e.g. a higher version) contributed by a different project than the leaf's
     * own. This pass re-merges each leaf dependency against every one of its ancestors' *merged*
     * versions so leaf metadata reflects the global, cross-project view rather than a stale
     * per-project snapshot.
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

    private fun buildResults(
        input: OwnershipPlannerInput,
        mainBuckets: MainBucketPlanResult,
        testBuckets: Map<String, Map<String, ResolvedDependency>>,
        androidTestBuckets: Map<String, Map<String, ResolvedDependency>>
    ): List<ResolveDependenciesResult> {
        val results = mutableListOf<ResolveDependenciesResult>()
        val reachableMainBucketsSnapshot = input.reachableMainBucketNamesByProject
            .mapValues { (_, bucketNames) -> bucketNames.toSortedSet() }

        fun buildResult(
            bucketName: String,
            deps: Map<String, ResolvedDependency>
        ): ResolveDependenciesResult {
            val kspDeps = if (bucketName == DEFAULT_VARIANT) precomputedKspDependencies else emptySet()
            return ResolveDependenciesResult(
                variantName = bucketName,
                dependencies = mapOf(
                    COMPILE.name to deps.values.toSortedSet(),
                    KSP.name to kspDeps
                ),
                reachableMainBucketsByProject = reachableMainBucketsSnapshot
            )
        }

        results.add(buildResult(DEFAULT_VARIANT, mainBuckets.defaultDeps))
        mainBuckets.hierarchyBuckets.forEach { (bucketName, deps) -> results.add(buildResult(bucketName, deps)) }
        mainBuckets.filteredPerLeafBuckets.forEach { (leaf, deps) -> results.add(buildResult(leaf, deps)) }
        testBuckets.forEach { (bucketName, deps) -> results.add(buildResult(bucketName, deps)) }
        androidTestBuckets.forEach { (bucketName, deps) -> results.add(buildResult(bucketName, deps)) }
        if (input.lintDeps.isNotEmpty()) results.add(buildResult(LINT_VARIANT, input.lintDeps))

        return results
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
