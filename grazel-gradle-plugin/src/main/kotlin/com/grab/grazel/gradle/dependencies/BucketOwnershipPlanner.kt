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

import com.grab.grazel.gradle.dependencies.model.OverrideTarget
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.hasSameResolvedArtifactIdentityAs
import com.grab.grazel.gradle.dependencies.model.hasSameResolvedOwnerIdentityAs
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.Test
import com.grab.grazel.gradle.variant.testSuffix

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

internal class BucketOwnershipPlanner(
    private val declaredDependencyMetadata: DeclaredDependencyMetadata,
    private val precomputedKspDependencies: Set<ResolvedDependency>
) {
    fun plan(input: OwnershipPlannerInput): List<ResolveDependenciesResult> {
        val mainBuckets = planMainBuckets(input)
        val testBuckets = planTestBuckets(
            input = input,
            variantType = Test,
            baseBucketName = TEST_VARIANT,
            leafClosures = input.leafUnitTestClosures,
            mainCoveredDepsByProject = mainBuckets.mainCoveredDepsByProject,
            aggregateMainCoveredDeps = mainBuckets.aggregateMainCoveredDeps,
            declaredTestDependenciesByBucket = input.declaredTestDependenciesByBucket
        )
        val androidTestBuckets = planTestBuckets(
            input = input,
            variantType = AndroidTest,
            baseBucketName = ANDROID_TEST_VARIANT,
            leafClosures = input.leafAndroidTestClosures,
            mainCoveredDepsByProject = mainBuckets.mainCoveredDepsByProject,
            aggregateMainCoveredDeps = mainBuckets.aggregateMainCoveredDeps,
            declaredTestDependenciesByBucket = input.declaredTestDependenciesByBucket,
            inheritedTestCoveredDeps = testBuckets
                .flatMap { (bucketName, deps) -> deps.asCoveredBy(bucketName) }
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

    private fun planMainBuckets(input: OwnershipPlannerInput): MainBucketPlanResult {
        val mainBucketVariants = declaredDependencyMetadata.mainBucketVariantsByProject()
        val declaredMainDependenciesByBucket = declaredDependencyMetadata
            .collectDeclaredMainDependenciesByProjectBucket(declaredDependencyMetadata.projects.keys)
        val mainBucketPlansByProject = DependencyBucketPlacementEngine().planByProject(
            variants = mainBucketVariants,
            hierarchyBucketClosures = input.hierarchyBucketClosures,
            leafClosures = input.leafClosures
        ).mapValues { (projectPath, plan) ->
            plan.withDeclaredMainMetadata(
                projectPath = projectPath,
                declaredMainDependenciesByBucket = declaredMainDependenciesByBucket
            )
        }
        val mainBucketPlans = mainBucketPlansByProject.values
        val declaredMetadataByOutputBucket = linkedMapOf<String, Map<String, ResolvedDependency>>()
        mainBucketPlansByProject.forEach { (projectPath, plan) ->
            declaredMetadataByOutputBucket.addDeclaredOutputMetadata(
                bucketName = plan.baseBucketName,
                metadata = declaredOutputMetadata(
                    projectPath = projectPath,
                    bucketName = plan.baseBucketName,
                    dependencies = plan.defaultBucket,
                    declaredDependenciesByBucket = declaredMainDependenciesByBucket
                )
            )
            plan.hierarchyBuckets.forEach { (bucketName, dependencies) ->
                declaredMetadataByOutputBucket.addDeclaredOutputMetadata(
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
                declaredMetadataByOutputBucket.addDeclaredOutputMetadata(
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
        val defaultDeps = mergeDependencyMaps(
            mainBucketPlans.map(DependencyBucketPlacementPlan::defaultBucket)
        ).withDeclaredMetadata(declaredMetadataByOutputBucket[DEFAULT_VARIANT].orEmpty())
        val hierarchyBuckets = mergeNamedBuckets(
            mainBucketPlans.map(DependencyBucketPlacementPlan::hierarchyBuckets)
        ).withDeclaredMetadataByBucket(declaredMetadataByOutputBucket)
            .withoutDeclaredPlaceholdersCoveredByDefault(defaultDeps)
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
                            dependencies.withoutDependenciesCoveredBy(
                                plan.leafAncestors[leafName]
                                    .orEmpty()
                                    .flatMap { ancestorName ->
                                        hierarchyBuckets[ancestorName].orEmpty().asCoveredBy(ancestorName)
                                    }
                            )
                        }
                        .filterValues(Map<String, ResolvedDependency>::isNotEmpty)
                }
            ),
            leafAncestorsByName = leafAncestorsByName,
            hierarchyBuckets = hierarchyBuckets
        ).withDeclaredMetadataByBucket(declaredMetadataByOutputBucket)
            .withoutDeclaredPlaceholdersCoveredByDefault(defaultDeps)
        val aggregateMainCoveredDeps = buildList {
            addAll(defaultDeps.asCoveredBy(DEFAULT_VARIANT))
            hierarchyBuckets.forEach { (bucketName, dependencies) ->
                addAll(dependencies.asCoveredBy(bucketName))
            }
            filteredPerLeafBuckets.forEach { (bucketName, dependencies) ->
                addAll(dependencies.asCoveredBy(bucketName))
            }
        }
        return MainBucketPlanResult(
            defaultDeps = defaultDeps,
            hierarchyBuckets = hierarchyBuckets,
            filteredPerLeafBuckets = filteredPerLeafBuckets,
            mainCoveredDepsByProject = mainCoveredDepsByProject,
            aggregateMainCoveredDeps = aggregateMainCoveredDeps
        )
    }

    private fun DependencyBucketPlacementPlan.withDeclaredMainMetadata(
        projectPath: String,
        declaredMainDependenciesByBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
    ): DependencyBucketPlacementPlan {
        return copy(
            defaultBucket = defaultBucket.withDeclaredMetadata(
                declaredOutputMetadata(
                    projectPath = projectPath,
                    bucketName = baseBucketName,
                    dependencies = defaultBucket,
                    declaredDependenciesByBucket = declaredMainDependenciesByBucket
                )
            ),
            hierarchyBuckets = hierarchyBuckets
                .mapValues { (bucketName, dependencies) ->
                    dependencies.withDeclaredMetadata(
                        declaredOutputMetadata(
                            projectPath = projectPath,
                            bucketName = bucketName,
                            dependencies = dependencies,
                            declaredDependenciesByBucket = declaredMainDependenciesByBucket
                        )
                    )
                }
                .toSortedMap(),
            leafBuckets = leafBuckets
                .mapValues { (bucketName, dependencies) ->
                    dependencies.withDeclaredMetadata(
                        declaredOutputMetadata(
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

    private fun MutableMap<String, Map<String, ResolvedDependency>>.addDeclaredOutputMetadata(
        bucketName: String,
        metadata: Map<String, ResolvedDependency>
    ) {
        if (metadata.isEmpty()) return
        this[bucketName] = this[bucketName]
            ?.let { existing -> unionDependencyMaps(existing, metadata) }
            ?: metadata
    }

    private fun Map<String, Map<String, ResolvedDependency>>.withDeclaredMetadataByBucket(
        declaredMetadataByOutputBucket: Map<String, Map<String, ResolvedDependency>>
    ): Map<String, Map<String, ResolvedDependency>> {
        if (isEmpty() || declaredMetadataByOutputBucket.isEmpty()) return this
        return mapValues { (bucketName, dependencies) ->
            dependencies.withDeclaredMetadata(declaredMetadataByOutputBucket[bucketName].orEmpty())
        }.toSortedMap()
    }

    private fun Map<String, ResolvedDependency>.withDeclaredMetadata(
        declaredDependencies: Map<String, ResolvedDependency>
    ): Map<String, ResolvedDependency> {
        if (isEmpty() || declaredDependencies.isEmpty()) return this
        return mapValues { (shortId, dependency) ->
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
        return dependencyMaps.fold(emptyMap()) { merged, dependencies ->
            unionDependencyMaps(merged, dependencies)
        }
    }

    private fun mergeNamedBuckets(
        bucketsByPlan: Iterable<Map<String, Map<String, ResolvedDependency>>>
    ): Map<String, Map<String, ResolvedDependency>> {
        val mergedBuckets = linkedMapOf<String, Map<String, ResolvedDependency>>()
        bucketsByPlan.forEach { buckets ->
            buckets.toSortedMap().forEach { (bucketName, dependencies) ->
                mergedBuckets[bucketName] = mergedBuckets[bucketName]
                    ?.let { existing -> unionDependencyMaps(existing, dependencies) }
                    ?: dependencies
            }
        }
        return mergedBuckets.toSortedMap()
    }

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
        return testBucketPlans(
            variantType = variantType,
            baseBucketName = baseBucketName,
            leafClosures = leafClosures,
            hierarchyBucketClosures = testHierarchyBucketClosuresFor(
                input = input,
                variantType = variantType,
                baseBucketName = baseBucketName
            )
        ).plannedTestBuckets(
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
        val testSuffix = variantType.testSuffix
        return input.testHierarchyBucketClosures.filterKeys { bucket ->
            bucket.bucketName == baseBucketName || bucket.bucketName.endsWith(testSuffix)
        }
    }

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
            .sorted()
            .firstOrNull()
            ?: baseBucketName
    }

    private fun concreteTestLeafClosures(
        leafClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        variantType: VariantType,
        baseBucketName: String
    ): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
        return leafClosures.entries.fold(
            linkedMapOf<ProjectDependencyBucket, Map<String, ResolvedDependency>>()
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
            mappedClosures[testBucket] = mappedClosures[testBucket]
                ?.let { existing -> unionDependencyMaps(existing, dependencies) }
                ?: dependencies
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

    private fun Map<String, DependencyBucketPlacementPlan>.plannedTestBuckets(
        mainCoveredDepsByProject: Map<String, List<CoveredDependency>>,
        aggregateMainCoveredDeps: List<CoveredDependency>,
        declaredTestDependenciesByBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>,
        inheritedTestCoveredDeps: List<CoveredDependency> = emptyList()
    ): Map<String, Map<String, ResolvedDependency>> {
        val buckets = linkedMapOf<String, Map<String, ResolvedDependency>>()
        val declaredMetadataByOutputBucket = linkedMapOf<String, Map<String, ResolvedDependency>>()
        toSortedMap().forEach { (projectPath, plan) ->
            val mainCoveredDeps = mainCoveredDepsByProject[projectPath].orEmpty()
            val plannedBuckets = linkedMapOf<String, Map<String, ResolvedDependency>>()

            fun addPlannedBucket(bucketName: String, dependencies: Map<String, ResolvedDependency>) {
                plannedBuckets[bucketName] = plannedBuckets[bucketName]
                    ?.let { existing -> unionDependencyMaps(existing, dependencies) }
                    ?: dependencies
            }

            addPlannedBucket(plan.baseBucketName, plan.defaultBucket)
            plan.hierarchyBuckets.toSortedMap().forEach { (bucketName, dependencies) ->
                addPlannedBucket(bucketName, dependencies)
            }
            plan.leafBuckets.toSortedMap().forEach { (bucketName, dependencies) ->
                addPlannedBucket(bucketName, dependencies)
            }
            plannedBuckets.forEach { (bucketName, dependencies) ->
                val testBucketNames = plan.testBucketNamesForTestBucket(
                    bucketName = bucketName
                )
                val visibleMainBucketNames = plan.visibleMainBucketNamesForTestBucket(
                    testBucketNames = testBucketNames
                )
                val visibleCoveredDepsByShortId = (
                    (mainCoveredDeps + aggregateMainCoveredDeps)
                        .filter { covered -> covered.bucketName in visibleMainBucketNames } +
                        inheritedTestCoveredDeps.filter { covered -> covered.bucketName in testBucketNames }
                    )
                    .groupByShortId()
                val declaredTestDependencies = declaredTestDependenciesByBucket[
                    ProjectDependencyBucket(projectPath, bucketName)
                ].orEmpty()
                val testOnlyDependencies = dependencies
                    .withoutTestDependenciesCoveredBy(
                        coveredByShortId = visibleCoveredDepsByShortId,
                        declaredTestDependencies = declaredTestDependencies
                    )
                    .toSortedMap()
                if (testOnlyDependencies.isNotEmpty()) {
                    declaredMetadataByOutputBucket.addDeclaredOutputMetadata(
                        bucketName = bucketName,
                        metadata = declaredTestDependencies.filterKeys { shortId ->
                            shortId in testOnlyDependencies
                        }
                    )
                    buckets[bucketName] = buckets[bucketName]
                        ?.let { existing -> unionDependencyMaps(existing, testOnlyDependencies) }
                        ?: testOnlyDependencies
                }
            }
        }
        return buckets
            .toSortedMap()
            .withDeclaredMetadataByBucket(declaredMetadataByOutputBucket)
    }

    private fun DependencyBucketPlacementPlan.testBucketNamesForTestBucket(
        bucketName: String
    ): Set<String> = setOf(bucketName) + bucketAncestors[bucketName].orEmpty()

    private fun DependencyBucketPlacementPlan.visibleMainBucketNamesForTestBucket(
        testBucketNames: Set<String>
    ): Set<String> {
        return buildSet {
            add(DEFAULT_VARIANT)
            testBucketNames.forEach { testBucketName ->
                if (testBucketName == baseBucketName) return@forEach
                add(testBucketName)
                if (testBucketName.endsWith(Test.testSuffix)) {
                    add(testBucketName.removeSuffix(Test.testSuffix))
                }
                if (testBucketName.endsWith(AndroidTest.testSuffix)) {
                    add(testBucketName.removeSuffix(AndroidTest.testSuffix))
                }
            }
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
        if (input.lintDeps.isNotEmpty()) results.add(buildResult("lint", input.lintDeps))

        return results
    }

    private data class MainBucketPlanResult(
        val defaultDeps: Map<String, ResolvedDependency>,
        val hierarchyBuckets: Map<String, Map<String, ResolvedDependency>>,
        val filteredPerLeafBuckets: Map<String, Map<String, ResolvedDependency>>,
        val mainCoveredDepsByProject: Map<String, List<CoveredDependency>>,
        val aggregateMainCoveredDeps: List<CoveredDependency>
    )
}

internal fun unionDependencyMaps(
    runtime: Map<String, ResolvedDependency>,
    compile: Map<String, ResolvedDependency>
): Map<String, ResolvedDependency> {
    if (compile.isEmpty()) return runtime
    if (runtime.isEmpty()) return compile
    val merged = runtime.toMutableMap()
    for ((shortId, compileDep) in compile) {
        val existing = merged[shortId]
        if (existing == null) {
            merged[shortId] = compileDep
        } else {
            merged[shortId] = mergeDependencyMetadataByMaxVersion(existing, compileDep)
        }
    }
    return merged
}

private fun Iterable<CoveredDependency>.groupByShortId(): Map<String, List<CoveredDependency>> =
    groupBy { it.dependency.shortId }

private fun Map<String, ResolvedDependency>.withoutTestDependenciesCoveredBy(
    coveredByShortId: Map<String, List<CoveredDependency>>,
    declaredTestDependencies: Map<String, ResolvedDependency>
): Map<String, ResolvedDependency> {
    val filtered = withoutDependenciesCoveredBy(coveredByShortId)
    if (filtered.isEmpty()) return filtered
    return filtered.filterNot { (shortId, dependency) ->
        val declaredTestDependency = declaredTestDependencies[shortId]
        coveredByShortId[dependency.shortId].orEmpty().any { covered ->
            when {
                dependency.isDeclaredMetadata() -> covered.canCoverDeclaredTestMetadata(dependency)
                declaredTestDependency == null -> covered.canCoverInheritedTestRoot(dependency)
                else -> covered.canCoverDeclaredTestRoot(
                    dependency = dependency,
                    declaredDependency = declaredTestDependency
                )
            }
        }
    }
}

private fun Map<String, ResolvedDependency>.withoutDependenciesCoveredBy(
    coveredByShortId: Map<String, List<CoveredDependency>>
): Map<String, ResolvedDependency> {
    return mapNotNull { (shortId, dependency) ->
        val coveringDependencies = coveredByShortId[shortId]
            .orEmpty()
            .filter { covered -> covered.canCover(dependency) }
        val exactCoveredDependency = coveringDependencies.firstOrNull { covered ->
            covered.dependency.hasSameResolvedArtifactIdentityAs(dependency)
        }
        val supersetClosureCoveredDependency = coveringDependencies.firstOrNull { covered ->
            covered.rootsSupersetClosureOf(dependency)
        }
        val coveredDependency = exactCoveredDependency
            ?: supersetClosureCoveredDependency
            ?: coveringDependencies.firstOrNull()
        when {
            coveredDependency == null -> shortId to dependency
            exactCoveredDependency != null -> null
            supersetClosureCoveredDependency != null -> null
            dependency.direct && coveredDependency.dependency.direct -> {
                shortId to dependency.copy(
                    overrideTarget = dependency.overrideTarget ?: coveredDependency.toOverrideTarget()
                )
            }
            else -> null
        }
    }
        .toMap()
}

private fun CoveredDependency.canCover(dependency: ResolvedDependency): Boolean {
    return (this.dependency.hasSameResolvedOwnerIdentityAs(dependency) ||
        this.dependency.canCoverDeclaredPlaceholder(dependency)) &&
        (!dependency.direct || this.dependency.direct)
}

private fun ResolvedDependency.canCoverDeclaredPlaceholder(dependency: ResolvedDependency): Boolean {
    return direct &&
        !isDeclaredMetadata() &&
        dependency.direct &&
        dependency.isDeclaredMetadata() &&
        shortId == dependency.shortId &&
        version == dependency.version &&
        requiresJetifier == dependency.requiresJetifier &&
        jetifierSource == dependency.jetifierSource &&
        dependency.excludeRules == excludeRules
}

private fun Map<String, Map<String, ResolvedDependency>>.withoutDeclaredPlaceholdersCoveredByDefault(
    defaultDeps: Map<String, ResolvedDependency>
): Map<String, Map<String, ResolvedDependency>> {
    if (isEmpty() || defaultDeps.isEmpty()) return this
    val defaultCoveredDepsByShortId = defaultDeps
        .asCoveredBy(DEFAULT_VARIANT)
        .groupByShortId()
    return mapValues { (_, dependencies) ->
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

private fun CoveredDependency.canCoverDeclaredTestMetadata(dependency: ResolvedDependency): Boolean {
    return this.dependency.direct &&
        dependency.isDeclaredMetadata() &&
        this.dependency.shortId == dependency.shortId &&
        this.dependency.version == dependency.version &&
        (dependency.excludeRules.isEmpty() || dependency.excludeRules == this.dependency.excludeRules)
}

private fun CoveredDependency.canCoverInheritedTestRoot(dependency: ResolvedDependency): Boolean {
    return this.dependency.direct &&
        dependency.direct &&
        this.dependency.shortId == dependency.shortId &&
        this.dependency.version == dependency.version &&
        this.dependency.repository == dependency.repository &&
        this.dependency.requiresJetifier == dependency.requiresJetifier &&
        this.dependency.jetifierSource == dependency.jetifierSource &&
        (dependency.excludeRules.isEmpty() || dependency.excludeRules == this.dependency.excludeRules)
}

private fun CoveredDependency.canCoverDeclaredTestRoot(
    dependency: ResolvedDependency,
    declaredDependency: ResolvedDependency
): Boolean {
    return canCoverInheritedTestRoot(dependency) &&
        (declaredDependency.excludeRules.isEmpty() || declaredDependency.excludeRules == this.dependency.excludeRules)
}

private fun CoveredDependency.rootsSupersetClosureOf(dependency: ResolvedDependency): Boolean {
    return this.dependency.direct &&
        dependency.direct &&
        this.dependency.dependencies.containsAll(dependency.dependencies)
}

private fun CoveredDependency.toOverrideTarget(): OverrideTarget {
    return mavenOverrideTarget(dependency.shortId, bucketName)
}
