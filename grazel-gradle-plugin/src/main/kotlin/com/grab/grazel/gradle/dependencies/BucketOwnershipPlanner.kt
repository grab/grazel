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

private data class MainBucketPlanResult(
    val defaultDeps: Map<String, ResolvedDependency>,
    val hierarchyBuckets: Map<String, Map<String, ResolvedDependency>>,
    val filteredPerLeafBuckets: Map<String, Map<String, ResolvedDependency>>,
    val mainCoveredDepsByProject: Map<String, List<CoveredDependency>>
) {
    fun coveredDependencies(): List<CoveredDependency> {
        return buildList {
            addAll(coveredDependenciesForBucket(defaultDeps, DEFAULT_VARIANT))
            hierarchyBuckets.forEach { (bucketName, dependencies) ->
                addAll(coveredDependenciesForBucket(dependencies, bucketName))
            }
            filteredPerLeafBuckets.forEach { (bucketName, dependencies) ->
                addAll(coveredDependenciesForBucket(dependencies, bucketName))
            }
        }
    }
}

internal class BucketOwnershipPlanner(
    private val declaredDependencyMetadata: DeclaredDependencyMetadata,
    private val precomputedKspDependencies: Set<ResolvedDependency>
) {
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
                            withoutDependenciesCoveredBy(
                                dependenciesByShortId = dependencies,
                                coveredDependencies = plan.leafAncestors[leafName]
                                    .orEmpty()
                                    .flatMap { ancestorName ->
                                        coveredDependenciesForBucket(
                                            dependenciesByShortId = hierarchyBuckets[ancestorName].orEmpty(),
                                            bucketName = ancestorName
                                        )
                                    }
                            )
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

    private fun DependencyBucketPlacementPlan.withDeclaredMainMetadata(
        projectPath: String,
        declaredMainDependenciesByBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
    ): DependencyBucketPlacementPlan {
        return copy(
            defaultBucket = applyDeclaredMetadata(
                dependencies = defaultBucket,
                declaredDependencies = declaredOutputMetadata(
                    projectPath = projectPath,
                    bucketName = baseBucketName,
                    dependencies = defaultBucket,
                    declaredDependenciesByBucket = declaredMainDependenciesByBucket
                )
            ),
            hierarchyBuckets = hierarchyBuckets
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
            leafBuckets = leafBuckets
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
        declaredMetadataByOutputBucket[bucketName] = declaredMetadataByOutputBucket[bucketName]
            ?.let { existing -> unionDependencyMaps(existing, metadata) }
            ?: metadata
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
                val outputBucketName = plan.outputBucketNameForTestBucket(bucketName)
                val testBucketNames = plan.testBucketNamesForTestBucket(
                    bucketName = bucketName
                )
                val visibleMainBucketNames = plan.visibleMainBucketNamesForTestBucket(
                    testBucketNames = testBucketNames
                )
                val visibleCoveredDepsByShortId = coveredDepsByShortIdFor(
                    visibleMainBucketNames + testBucketNames
                )
                val leafCoveredDepsByShortId = plan
                    .concreteTestLeafNamesFor(bucketName)
                    .map { leafName ->
                        val leafTestBucketNames = plan.testBucketNamesForTestBucket(
                            bucketName = leafName
                        )
                        val leafVisibleMainBucketNames = plan.visibleMainBucketNamesForTestBucket(
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
                    buckets[outputBucketName] = buckets[outputBucketName]
                        ?.let { existing -> unionDependencyMaps(existing, testOnlyDependencies) }
                        ?: testOnlyDependencies
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
            }
        }
    }

    private fun DependencyBucketPlacementPlan.concreteTestLeafNamesFor(
        bucketName: String
    ): Set<String> {
        return bucketDescendantLeaves[bucketName]
            .orEmpty()
            .ifEmpty {
                if (bucketName in leafAncestors) setOf(bucketName) else emptySet()
            }
    }

    private fun DependencyBucketPlacementPlan.outputBucketNameForTestBucket(
        bucketName: String
    ): String {
        if (bucketName == baseBucketName || isTypedTestBucket(bucketName)) {
            return bucketName
        }
        val scopedBucketName = bucketName + testSuffixForBaseBucket()
        return if (isTypedTestBucket(scopedBucketName)) {
            scopedBucketName
        } else {
            baseBucketName
        }
    }

    private fun DependencyBucketPlacementPlan.isTypedTestBucket(bucketName: String): Boolean {
        return variantTypesByBucketName[bucketName] == testVariantTypeForBaseBucket()
    }

    private fun DependencyBucketPlacementPlan.testSuffixForBaseBucket(): String {
        return when (baseBucketName) {
            TEST_VARIANT -> Test.testSuffix
            ANDROID_TEST_VARIANT -> AndroidTest.testSuffix
            else -> error("Unsupported test base bucket $baseBucketName")
        }
    }

    private fun DependencyBucketPlacementPlan.testVariantTypeForBaseBucket(): VariantType {
        return when (baseBucketName) {
            TEST_VARIANT -> Test
            ANDROID_TEST_VARIANT -> AndroidTest
            else -> error("Unsupported test base bucket $baseBucketName")
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

private fun withoutTestDependenciesCoveredBy(
    testDependencies: Map<String, ResolvedDependency>,
    coveredByShortId: Map<String, List<CoveredDependency>>,
    declaredTestDependencies: Map<String, ResolvedDependency>
): Map<String, ResolvedDependency> {
    val filtered = withoutDependenciesCoveredByShortId(
        dependenciesByShortId = testDependencies,
        coveredByShortId = coveredByShortId
    )
    val scopedSiblingClosureDependenciesByShortId = scopedSiblingClosureDependenciesByShortId(testDependencies)
    val restoredDependencies = testDependencies.filter { (shortId, dependency) ->
        dependency.direct && shortId !in filtered && !coveredByShortId[shortId].orEmpty().any { covered ->
            covered.canCoverTestDependency(
                dependency = dependency,
                declaredTestDependency = declaredTestDependencies[shortId],
                scopedSiblingClosureDependencies = scopedSiblingClosureDependenciesByShortId[shortId].orEmpty()
            )
        }
    }
    return (filtered + restoredDependencies).filterNot { (shortId, dependency) ->
        val declaredTestDependency = declaredTestDependencies[shortId]
        coveredByShortId[dependency.shortId].orEmpty().any { covered ->
            covered.canCoverTestDependency(
                dependency = dependency,
                declaredTestDependency = declaredTestDependency,
                scopedSiblingClosureDependencies = scopedSiblingClosureDependenciesByShortId[shortId].orEmpty()
            )
        }
    }
}

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
                covered.canCoverTestDependency(
                    dependency = dependency,
                    declaredTestDependency = declaredTestDependency,
                    scopedSiblingClosureDependencies = scopedSiblingClosureDependenciesByShortId[shortId].orEmpty()
                )
            }
        }
    }
}

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

private fun CoveredDependency.canCoverTestDependency(
    dependency: ResolvedDependency,
    declaredTestDependency: ResolvedDependency?,
    scopedSiblingClosureDependencies: Set<String> = emptySet()
): Boolean {
    return when {
        dependency.isDeclaredMetadata() -> canCoverDeclaredTestMetadata(dependency)
        declaredTestDependency == null -> canCoverInheritedTestRoot(
            dependency = dependency,
            scopedSiblingClosureDependencies = scopedSiblingClosureDependencies
        )
        else -> canCoverDeclaredTestRoot(
            dependency = dependency,
            declaredDependency = declaredTestDependency,
            scopedSiblingClosureDependencies = scopedSiblingClosureDependencies
        )
    }
}

private fun withoutDeclaredPlaceholdersCoveredByDefault(
    dependenciesByBucket: Map<String, Map<String, ResolvedDependency>>,
    defaultDeps: Map<String, ResolvedDependency>
): Map<String, Map<String, ResolvedDependency>> {
    if (dependenciesByBucket.isEmpty() || defaultDeps.isEmpty()) return dependenciesByBucket
    val defaultCoveredDepsByShortId = defaultDeps
        .let { dependencies -> coveredDependenciesForBucket(dependencies, DEFAULT_VARIANT) }
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

private fun CoveredDependency.canCoverDeclaredTestMetadata(dependency: ResolvedDependency): Boolean {
    return this.dependency.direct &&
        dependency.isDeclaredMetadata() &&
        this.dependency.shortId == dependency.shortId &&
        this.dependency.version == dependency.version &&
        (dependency.excludeRules.isEmpty() || dependency.excludeRules == this.dependency.excludeRules)
}

private fun CoveredDependency.canCoverInheritedTestRoot(
    dependency: ResolvedDependency,
    scopedSiblingClosureDependencies: Set<String> = emptySet()
): Boolean {
    return this.dependency.direct &&
        dependency.direct &&
        this.dependency.shortId == dependency.shortId &&
        this.dependency.version == dependency.version &&
        this.dependency.repository == dependency.repository &&
        this.dependency.requiresJetifier == dependency.requiresJetifier &&
        this.dependency.jetifierSource == dependency.jetifierSource &&
        (this.dependency.dependencies + scopedSiblingClosureDependencies).containsAll(dependency.dependencies) &&
        (dependency.excludeRules.isEmpty() || dependency.excludeRules == this.dependency.excludeRules)
}

private fun CoveredDependency.canCoverDeclaredTestRoot(
    dependency: ResolvedDependency,
    declaredDependency: ResolvedDependency,
    scopedSiblingClosureDependencies: Set<String> = emptySet()
): Boolean {
    return canCoverInheritedTestRoot(
        dependency = dependency,
        scopedSiblingClosureDependencies = scopedSiblingClosureDependencies
    ) &&
        (declaredDependency.excludeRules.isEmpty() || declaredDependency.excludeRules == this.dependency.excludeRules)
}
