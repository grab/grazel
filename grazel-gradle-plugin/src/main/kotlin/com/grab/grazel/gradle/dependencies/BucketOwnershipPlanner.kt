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
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.Test
import com.grab.grazel.gradle.variant.testSuffix
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
        val testBuckets = planTestBuckets(
            input = input,
            variantType = Test,
            baseBucketName = TEST_VARIANT,
            leafClosures = input.leafUnitTestClosures,
            mainCoveredDepsByProject = mainBuckets.mainCoveredDepsByProject,
            aggregateMainCoveredDeps = aggregateMainCoveredDeps,
            declaredTestDependenciesByBucket = input.declaredTestDependenciesByBucket
        )
        val androidTestBuckets = planTestBuckets(
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

    private fun variantsFor(projectPath: String): List<DeclaredVariantDependencyMetadata> =
        declaredDependencyMetadata.projects[projectPath]?.variants.orEmpty()

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

    private fun addDeclaredOutputMetadata(
        declaredMetadataByOutputBucket: MutableMap<String, Map<String, ResolvedDependency>>,
        bucketName: String,
        metadata: Map<String, ResolvedDependency>
    ) {
        if (metadata.isEmpty()) return
        declaredMetadataByOutputBucket.mergeBucket(bucketName, metadata)
    }

    private fun applyDeclaredMetadataByBucket(
        dependenciesByBucket: Map<String, Map<String, ResolvedDependency>>,
        declaredMetadataByOutputBucket: Map<String, Map<String, ResolvedDependency>>
    ): Map<String, Map<String, ResolvedDependency>> {
        if (dependenciesByBucket.isEmpty() || declaredMetadataByOutputBucket.isEmpty()) return dependenciesByBucket
        return dependenciesByBucket.mapValues { (bucketName, dependencies) ->
            applyDeclaredMetadata(
                dependencies = dependencies,
                declaredDependencies = declaredMetadataByOutputBucket[bucketName].orEmpty()
            )
        }.toSortedMap()
    }

    private fun applyDeclaredMetadata(
        dependencies: Map<String, ResolvedDependency>,
        declaredDependencies: Map<String, ResolvedDependency>
    ): Map<String, ResolvedDependency> {
        if (dependencies.isEmpty() || declaredDependencies.isEmpty()) return dependencies
        return dependencies.mapValues { (shortId, dependency) ->
            declaredDependencies[shortId]
                ?.let { declaredDependency ->
                    mergeDependencyMetadataByMaxVersion(dependency, declaredDependency)
                }
                ?: dependency
        }.toSortedMap()
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

    private fun planTestBuckets(
        input: OwnershipPlannerInput,
        variantType: VariantType,
        baseBucketName: String,
        leafClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        mainCoveredDepsByProject: Map<String, List<CoveredDependency>>,
        aggregateMainCoveredDeps: List<CoveredDependency>,
        declaredTestDependenciesByBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        inheritedTestCoveredDeps: List<CoveredDependency> = emptyList()
    ): Map<String, Map<String, ResolvedDependency>> {
        return plannedTestBuckets(
            testBucketPlansByProject = testBucketPlans(
                variantType = variantType,
                baseBucketName = baseBucketName,
                leafClosures = leafClosures,
                hierarchyBucketClosures = testHierarchyBucketClosuresFor(
                    input = input,
                    variantType = variantType,
                    baseBucketName = baseBucketName
                )
            ),
            baseBucketName = baseBucketName,
            mainCoveredDepsByProject = mainCoveredDepsByProject,
            aggregateMainCoveredDeps = aggregateMainCoveredDeps,
            declaredTestDependenciesByBucket = declaredTestDependenciesByBucket,
            inheritedTestCoveredDeps = inheritedTestCoveredDeps
        )
    }

    private fun testHierarchyBucketClosuresFor(
        input: OwnershipPlannerInput,
        variantType: VariantType,
        baseBucketName: String
    ): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
        val testBucketNames = typedTestBucketNames(
            variantType = variantType,
            baseBucketName = baseBucketName
        )
        return input.testHierarchyBucketClosures.filterKeys { bucket ->
            bucket.bucketName in testBucketNames
        }
    }

    private fun typedTestBucketNames(
        variantType: VariantType,
        baseBucketName: String
    ): Set<String> {
        return declaredDependencyMetadata.projects
            .values
            .asSequence()
            .flatMap { projectMetadata -> projectMetadata.variants.asSequence() }
            .filter { variant -> variant.variantType == variantType }
            .mapTo(sortedSetOf(), DeclaredVariantDependencyMetadata::name)
            .apply { add(baseBucketName) }
    }

    /**
     * A main leaf variant (e.g. `demoDebug`) has no test closure of its own; test dependencies are
     * resolved against a *test* leaf variant (e.g. `demoDebugAndroidTest`) that extends it. When
     * several declared test leaves extend the same main leaf, [minOrNull] picks the
     * lexicographically first name as a deterministic tie-break - callers only need one concrete
     * name per main leaf, and any of the candidates would resolve to the same underlying closure,
     * so the choice is arbitrary but must be stable across runs. Falls back to [baseBucketName]
     * (e.g. "test"/"androidTest") when no test leaf extends this main leaf at all.
     */
    private fun concreteTestLeafName(
        projectPath: String,
        mainLeafName: String,
        variantType: VariantType,
        baseBucketName: String
    ): String {
        return variantsFor(projectPath)
            .asSequence()
            .filter { variant -> variant.variantType == variantType }
            .filter(DeclaredVariantDependencyMetadata::androidLeafVariant)
            .filter { variant -> mainLeafName in variant.extendsFrom || variant.name == mainLeafName }
            .map(DeclaredVariantDependencyMetadata::name)
            .minOrNull()
            ?: baseBucketName
    }

    private fun concreteTestLeafClosures(
        leafClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        variantType: VariantType,
        baseBucketName: String
    ): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
        return leafClosures.entries.fold(
            linkedMapOf()
        ) { mappedClosures, (bucket, dependencies) ->
            val testBucket = ProjectDependencyBucket(
                projectPath = bucket.projectPath,
                bucketName = concreteTestLeafName(
                    projectPath = bucket.projectPath,
                    mainLeafName = bucket.bucketName,
                    variantType = variantType,
                    baseBucketName = baseBucketName
                )
            )
            mappedClosures.mergeBucket(testBucket, dependencies)
            mappedClosures
        }
    }

    private fun testBucketPlans(
        variantType: VariantType,
        baseBucketName: String,
        leafClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        hierarchyBucketClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
    ): Map<String, DependencyBucketPlacementPlan> {
        return DependencyBucketPlacementEngine().planByProject(
            variants = declaredDependencyMetadata.testBucketVariantsByProject(
                variantType = variantType,
                baseBucketName = baseBucketName
            ),
            hierarchyBucketClosures = hierarchyBucketClosures,
            leafClosures = concreteTestLeafClosures(
                leafClosures = leafClosures,
                variantType = variantType,
                baseBucketName = baseBucketName
            ),
            baseBucketName = baseBucketName
        )
    }

    /**
     * For each project's test bucket placement plan, subtracts every dependency that's already
     * reachable from the buckets a Gradle test source set can actually see, so only genuinely
     * test-only dependencies survive into the output. For every planned bucket this computes two
     * independent coverage views and combines them:
     *
     * - "visible" coverage ([withoutTestDependenciesCoveredBy]): deps covered by this bucket's own
     *   ancestor chain (default + hierarchy ancestors) plus any already-covered main/inherited-test
     *   deps for this bucket's own name and ancestors.
     * - "every leaf" coverage ([withoutTestDependenciesCoveredByEveryLeaf]): for a non-leaf test
     *   bucket, a dependency is only dropped if *all* of its concrete descendant test leaves
     *   independently see it covered too - a dependency covered on only some leaves must stay so
     *   those leaves that need it still get it.
     *
     * Each candidate bucket's dependencies are also remapped to an [outputBucketNameForTestBucket]
     * before merging, since several distinct internal bucket names can collapse onto the same
     * emitted test source set. Declared test dependency metadata is looked up under both the raw
     * bucket name and its output name because declarations may be recorded against either.
     */
    private fun plannedTestBuckets(
        testBucketPlansByProject: Map<String, DependencyBucketPlacementPlan>,
        baseBucketName: String,
        mainCoveredDepsByProject: Map<String, List<CoveredDependency>>,
        aggregateMainCoveredDeps: List<CoveredDependency>,
        declaredTestDependenciesByBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        inheritedTestCoveredDeps: List<CoveredDependency> = emptyList()
    ): Map<String, Map<String, ResolvedDependency>> {
        val buckets = linkedMapOf<String, Map<String, ResolvedDependency>>()
        val declaredMetadataByOutputBucket = linkedMapOf<String, Map<String, ResolvedDependency>>()
        testBucketPlansByProject.toSortedMap().forEach { (projectPath, plan) ->
            val mainCoveredDeps = mainCoveredDepsByProject[projectPath].orEmpty()
            val coveredDepsByBucket = (
                mainCoveredDeps +
                    aggregateMainCoveredDeps +
                    inheritedTestCoveredDeps
                )
                .groupBy(CoveredDependency::bucketName)
            val plannedBuckets = linkedMapOf<String, Map<String, ResolvedDependency>>()

            fun coveredDepsByShortIdFor(bucketNames: Set<String>): Map<String, List<CoveredDependency>> {
                return bucketNames
                    .asSequence()
                    .flatMap { bucketName -> coveredDepsByBucket[bucketName].orEmpty().asSequence() }
                    .toList()
                    .let(::groupCoveredDependenciesByShortId)
            }

            fun addPlannedBucket(bucketName: String, dependencies: Map<String, ResolvedDependency>) {
                plannedBuckets.mergeBucket(bucketName, dependencies)
            }

            addPlannedBucket(plan.baseBucketName, plan.defaultBucket)
            plan.hierarchyBuckets.toSortedMap().forEach { (bucketName, dependencies) ->
                addPlannedBucket(bucketName, dependencies)
            }
            plan.leafBuckets.toSortedMap().forEach { (bucketName, dependencies) ->
                addPlannedBucket(bucketName, dependencies)
            }
            plannedBuckets.forEach { (bucketName, dependencies) ->
                val outputBucketName = outputBucketNameForTestBucket(plan, bucketName)
                val testBucketNames = testBucketNamesForTestBucket(
                    plan = plan,
                    bucketName = bucketName
                )
                val visibleMainBucketNames = visibleMainBucketNamesForTestBucket(
                    plan = plan,
                    testBucketNames = testBucketNames
                )
                val visibleCoveredDepsByShortId = coveredDepsByShortIdFor(
                    visibleMainBucketNames + testBucketNames
                )
                val leafCoveredDepsByShortId = concreteTestLeafNamesFor(
                    plan = plan,
                    bucketName = bucketName
                )
                    .map { leafName ->
                        val leafTestBucketNames = testBucketNamesForTestBucket(
                            plan = plan,
                            bucketName = leafName
                        )
                        val leafVisibleMainBucketNames = visibleMainBucketNamesForTestBucket(
                            plan = plan,
                            testBucketNames = leafTestBucketNames
                        )
                        coveredDepsByShortIdFor(
                            leafVisibleMainBucketNames + leafTestBucketNames
                        )
                    }
                val declaredTestDependencies = declaredTestDependenciesByBucket[
                    ProjectDependencyBucket(projectPath, bucketName)
                ].orEmpty() + declaredTestDependenciesByBucket[
                    ProjectDependencyBucket(projectPath, outputBucketName)
                ].orEmpty()
                val visibleMainOnlyDependencies = withoutTestDependenciesCoveredBy(
                    testDependencies = dependencies,
                    coveredByShortId = visibleCoveredDepsByShortId,
                    declaredTestDependencies = declaredTestDependencies
                )
                val testOnlyDependencies = withoutTestDependenciesCoveredByEveryLeaf(
                    testDependencies = visibleMainOnlyDependencies,
                    leafCoveredDepsByShortId = leafCoveredDepsByShortId,
                    declaredTestDependencies = declaredTestDependencies
                )
                    .toSortedMap()
                if (testOnlyDependencies.isNotEmpty()) {
                    addDeclaredOutputMetadata(
                        declaredMetadataByOutputBucket = declaredMetadataByOutputBucket,
                        bucketName = outputBucketName,
                        metadata = declaredTestDependencies.filterKeys { shortId ->
                            shortId in testOnlyDependencies
                        }
                    )
                    buckets.mergeBucket(outputBucketName, testOnlyDependencies)
                }
            }
        }
        return applyDeclaredMetadataByBucket(
            dependenciesByBucket = withoutMergedBaseTestDependenciesCoveredBy(
                testDependenciesByBucket = buckets.toSortedMap(),
                baseBucketName = baseBucketName,
                aggregateMainCoveredDeps = aggregateMainCoveredDeps,
                inheritedTestCoveredDeps = inheritedTestCoveredDeps
            ),
            declaredMetadataByOutputBucket = declaredMetadataByOutputBucket
        )
    }

    private fun testBucketNamesForTestBucket(
        plan: DependencyBucketPlacementPlan,
        bucketName: String
    ): Set<String> = setOf(bucketName) + plan.bucketAncestors[bucketName].orEmpty()

    private fun visibleMainBucketNamesForTestBucket(
        plan: DependencyBucketPlacementPlan,
        testBucketNames: Set<String>
    ): Set<String> {
        return buildSet {
            add(DEFAULT_VARIANT)
            testBucketNames.forEach { testBucketName ->
                if (testBucketName == plan.baseBucketName) return@forEach
                add(testBucketName)
            }
        }
    }

    private fun concreteTestLeafNamesFor(
        plan: DependencyBucketPlacementPlan,
        bucketName: String
    ): Set<String> {
        return plan.bucketDescendantLeaves[bucketName]
            .orEmpty()
            .ifEmpty {
                if (bucketName in plan.leafAncestors) setOf(bucketName) else emptySet()
            }
    }

    /**
     * A placement plan may contain internal bucket names (default, hierarchy, leaf) that don't
     * correspond 1:1 to an emitted test source set. This decides where a bucket's dependencies
     * should actually surface:
     * - the plan's own base bucket name, or any bucket already typed as this test variant, is
     *   emitted unchanged;
     * - otherwise, try suffixing the name for this test variant type (e.g. `demo` ->
     *   `demoAndroidTest`) - this is how a main hierarchy bucket maps to its corresponding typed
     *   test bucket;
     * - if that suffixed name isn't itself a real typed test bucket (no such variant was declared),
     *   collapse everything down to the plan's base bucket name rather than emitting a name nothing
     *   else recognizes.
     */
    private fun outputBucketNameForTestBucket(
        plan: DependencyBucketPlacementPlan,
        bucketName: String
    ): String {
        if (bucketName == plan.baseBucketName || isTypedTestBucket(plan, bucketName)) {
            return bucketName
        }
        val scopedBucketName = bucketName + testVariantTypeForBaseBucket(plan).testSuffix
        return if (isTypedTestBucket(plan, scopedBucketName)) {
            scopedBucketName
        } else {
            plan.baseBucketName
        }
    }

    private fun isTypedTestBucket(
        plan: DependencyBucketPlacementPlan,
        bucketName: String
    ): Boolean {
        return plan.variantTypesByBucketName[bucketName] == testVariantTypeForBaseBucket(plan)
    }

    private fun testVariantTypeForBaseBucket(plan: DependencyBucketPlacementPlan): VariantType {
        return when (plan.baseBucketName) {
            TEST_VARIANT -> Test
            ANDROID_TEST_VARIANT -> AndroidTest
            else -> error("Unsupported test base bucket ${plan.baseBucketName}")
        }
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

internal fun unionDependencyMaps(
    runtime: Map<String, ResolvedDependency>,
    compile: Map<String, ResolvedDependency>
): Map<String, ResolvedDependency> {
    if (compile.isEmpty()) return runtime
    if (runtime.isEmpty()) return compile
    return listOf(runtime, compile).merge(::mergeDependencyMetadataByMaxVersion)
}

private fun <K> MutableMap<K, Map<String, ResolvedDependency>>.mergeBucket(
    key: K,
    dependencies: Map<String, ResolvedDependency>
) {
    this[key] = this[key]?.let { existing -> unionDependencyMaps(existing, dependencies) } ?: dependencies
}

/**
 * Two-pass filter over a test bucket's dependencies against the deps already covered elsewhere:
 *
 * 1. [Coverage.subtract] does the generic subtraction, which can be too eager -
 *    it may drop a *direct* test dependency merely because some covering bucket happens to also
 *    resolve the same shortId, even if that covering entry can't actually satisfy this dependency's
 *    specific requirements (excludes, jetifier, exact closure).
 * 2. So any direct dependency the first pass removed is re-checked with [CoveredDependency.canCoverTest],
 *    which additionally recognizes deps whose transitive closure is satisfied only with the help of
 *    "scoped sibling closure" reasoning (see [scopedSiblingClosureDependenciesByShortId]); anything
 *    that still isn't genuinely coverable is restored.
 *
 * The final result re-applies [CoveredDependency.canCoverTest] to the union of both passes, so a dependency
 * is dropped only when it is truly coverable by this rule, not merely by shortId collision.
 */
private fun withoutTestDependenciesCoveredBy(
    testDependencies: Map<String, ResolvedDependency>,
    coveredByShortId: Map<String, List<CoveredDependency>>,
    declaredTestDependencies: Map<String, ResolvedDependency>
): Map<String, ResolvedDependency> {
    val filtered = Coverage.ofGrouped(coveredByShortId).subtract(testDependencies)
    val scopedSiblingClosureDependenciesByShortId = scopedSiblingClosureDependenciesByShortId(testDependencies)
    val restoredDependencies = testDependencies.filter { (shortId, dependency) ->
        dependency.direct && shortId !in filtered && !coveredByShortId[shortId].orEmpty().any { covered ->
            covered.canCoverTest(
                dependency = dependency,
                declaredTestDependency = declaredTestDependencies[shortId],
                scopedSiblingClosureDependencies = scopedSiblingClosureDependenciesByShortId[shortId].orEmpty()
            )
        }
    }
    return (filtered + restoredDependencies).filterNot { (shortId, dependency) ->
        val declaredTestDependency = declaredTestDependencies[shortId]
        coveredByShortId[dependency.shortId].orEmpty().any { covered ->
            covered.canCoverTest(
                dependency = dependency,
                declaredTestDependency = declaredTestDependency,
                scopedSiblingClosureDependencies = scopedSiblingClosureDependenciesByShortId[shortId].orEmpty()
            )
        }
    }
}

/**
 * For a non-leaf test bucket, a dependency can only be safely dropped if *every* concrete
 * descendant test leaf independently covers it too - dropping it based on only some leaves
 * covering it would silently starve the leaves that don't, since a non-leaf bucket's output is
 * shared by all its leaves. Hence the `all { ... }` (not `any`): this is the "every leaf must
 * agree" closure invariant, and reversing it to `any` would over-remove dependencies that some
 * leaves still need directly.
 */
private fun withoutTestDependenciesCoveredByEveryLeaf(
    testDependencies: Map<String, ResolvedDependency>,
    leafCoveredDepsByShortId: List<Map<String, List<CoveredDependency>>>,
    declaredTestDependencies: Map<String, ResolvedDependency>
): Map<String, ResolvedDependency> {
    if (testDependencies.isEmpty() || leafCoveredDepsByShortId.isEmpty()) return testDependencies
    val scopedSiblingClosureDependenciesByShortId = scopedSiblingClosureDependenciesByShortId(testDependencies)
    return testDependencies.filterNot { (shortId, dependency) ->
        val declaredTestDependency = declaredTestDependencies[shortId]
        leafCoveredDepsByShortId.all { coveredByShortId ->
            coveredByShortId[shortId].orEmpty().any { covered ->
                covered.canCoverTest(
                    dependency = dependency,
                    declaredTestDependency = declaredTestDependency,
                    scopedSiblingClosureDependencies = scopedSiblingClosureDependenciesByShortId[shortId].orEmpty()
                )
            }
        }
    }
}

/**
 * A cleanup pass that runs *after* all per-project test buckets have been merged into
 * [testDependenciesByBucket]: the merge can reintroduce a dependency into the base test bucket
 * (e.g. plain "test") that is already covered by default or by an inherited test bucket, because
 * that coverage information wasn't visible while each project's bucket was being filtered in
 * isolation. Only the base bucket is re-checked here (hierarchy/leaf buckets were already filtered
 * per-project against their own ancestors). Android test additionally sees unit test's dependencies
 * as coverage - a dependency already provided via `test` doesn't need to be redeclared for
 * `androidTest` - which is why [visibleCoveredBucketNames] special-cases [ANDROID_TEST_VARIANT].
 */
private fun withoutMergedBaseTestDependenciesCoveredBy(
    testDependenciesByBucket: Map<String, Map<String, ResolvedDependency>>,
    baseBucketName: String,
    aggregateMainCoveredDeps: List<CoveredDependency>,
    inheritedTestCoveredDeps: List<CoveredDependency>
): Map<String, Map<String, ResolvedDependency>> {
    val baseBucket = testDependenciesByBucket[baseBucketName] ?: return testDependenciesByBucket
    val visibleCoveredBucketNames = buildSet {
        add(DEFAULT_VARIANT)
        if (baseBucketName == ANDROID_TEST_VARIANT) {
            add(TEST_VARIANT)
        }
    }
    val coveredByShortId = (aggregateMainCoveredDeps + inheritedTestCoveredDeps)
        .filter { covered -> covered.bucketName in visibleCoveredBucketNames }
        .let(::groupCoveredDependenciesByShortId)
    if (coveredByShortId.isEmpty()) return testDependenciesByBucket

    val filteredBaseBucket = withoutTestDependenciesCoveredBy(
        testDependencies = baseBucket,
        coveredByShortId = coveredByShortId,
        declaredTestDependencies = emptyMap()
    )
        .toSortedMap()
    if (filteredBaseBucket == baseBucket) return testDependenciesByBucket

    return testDependenciesByBucket.toMutableMap()
        .apply {
            if (filteredBaseBucket.isEmpty()) {
                remove(baseBucketName)
            } else {
                this[baseBucketName] = filteredBaseBucket
            }
        }
        .toSortedMap()
}

/**
 * A transitive dependency notation that appears in more than one root's own declared-or-transitive
 * closure within the same bucket is a "sibling-shared" transitive: several direct roots each
 * (partially) pull it in. Counting occurrences and comparing against 1 (self) + (1 if already in
 * that root's own closure) lets [canCoverInheritedTestRoot] treat such a notation as effectively
 * part of a root's closure even though it isn't literally listed there - covering the common case
 * where a covering bucket's root closure doesn't itself list a transitive dependency that a sibling
 * root in this bucket already accounts for. Without this, coverage comparisons would be too strict
 * and would keep dependencies that are, in practice, already fully provided.
 */
private fun scopedSiblingClosureDependenciesByShortId(
    dependencies: Map<String, ResolvedDependency>
): Map<String, Set<String>> {
    val closureDependencyCounts = dependencies.values
        .asSequence()
        .flatMap { dependency ->
            sequenceOf(dependency.toDependencyNotation()) + dependency.dependencies.asSequence()
        }
        .groupingBy { dependencyNotation -> dependencyNotation }
        .eachCount()
    if (closureDependencyCounts.isEmpty()) return emptyMap()

    return dependencies.mapValues { (_, dependency) ->
        closureDependencyCounts
            .asSequence()
            .filter { (dependencyNotation, count) ->
                val candidateContribution =
                    if (dependencyNotation == dependency.toDependencyNotation()) 1 else 0
                val candidateClosureContribution = if (dependencyNotation in dependency.dependencies) 1 else 0
                count > candidateContribution + candidateClosureContribution
            }
            .mapTo(sortedSetOf()) { (dependencyNotation, _) -> dependencyNotation }
    }
}

private fun ResolvedDependency.toDependencyNotation(): String =
    "$id:$repository:$requiresJetifier:$jetifierSource"

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
