/*
 * Copyright 2024 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.OverrideTarget
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.hasSameResolvedArtifactIdentityAs
import com.grab.grazel.gradle.dependencies.model.hasSameResolvedOwnerIdentityAs
import com.grab.grazel.gradle.dependencies.model.intersectWith
import com.grab.grazel.gradle.dependencies.model.versionInfo
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.JvmBuild
import com.grab.grazel.gradle.variant.VariantType.Test
import com.grab.grazel.gradle.variant.testSuffix
import org.gradle.api.logging.Logger
import java.util.TreeSet

/**
 * Resolves all external dependencies for the migratable project set from task-wired workspace
 * dependency roots, then buckets them via set-intersection logic.
 *
 * The root inputs are prepared by the workspace dependency input registrar from app and standalone
 * `com.android.test` binary roots, plus task-produced declared metadata and KSP metadata. Bucket
 * output uses set-intersection logic: default, per-build-type, per-flavor, per-leaf residual, test,
 * androidTest, lint, and global KSP.
 *
 * The downstream [ComputeWorkspaceDependencies] pipeline consumes the synthetic bucket results.
 *
 * @param logger Task logger used for diagnostics.
 * @param declaredDependencyMetadata Serialized declaration and variant topology metadata produced
 *   before this task action. This keeps variant enumeration out of the workspace computation task.
 */
internal class AggregatedDependencyResolver(
    private val logger: Logger,
    private val declaredDependencyMetadata: DeclaredDependencyMetadata = DeclaredDependencyMetadata.EMPTY,
    private val precomputedKspDependencies: Set<ResolvedDependency> = emptySet(),
    private val workspaceDependencyRoots: List<AggregatedDependencyRoot> = emptyList()
) {

    /**
     * Resolve all variants across all migratable projects by resolving app module leaf
     * RuntimeClasspath configurations and bucketing the results.
     *
     * @return One [ResolveDependenciesResult] per synthetic bucket name (e.g. "default", "debug",
     *   "test", "androidTest", "lint", flavor names, or leaf-specific names), covering COMPILE and
     *   KSP scopes.
     */
    fun resolve(): List<ResolveDependenciesResult> = ResolutionSession().resolve()

    private inner class ResolutionSession {
        private val projectMetadataByPath = declaredDependencyMetadata.projects
        private val migratableProjectPaths = projectMetadataByPath.keys.sorted()
        private val projectPathsByDescendingLength = migratableProjectPaths.sortedByDescending(String::length)
        private val parsedDeclaredProjectDependencyEdges = mutableMapOf<String, DeclaredProjectDependencyEdge>()
        private val ignoredDeclaredProjectDependencyEdges = mutableSetOf<String>()
        private val declaredProjectDependencyEdgesCache =
            mutableMapOf<Triple<String, Set<String>, Boolean>, List<DeclaredProjectDependencyEdge>>()
        private val mainBuildTypeNamesByProject = migratableProjectPaths.associateWith { projectPath ->
            variantsFor(projectPath)
                .asSequence()
                .filter { variant -> variant.variantType == AndroidBuild }
                .filter(DeclaredVariantDependencyMetadata::androidLeafVariant)
                .mapNotNull(DeclaredVariantDependencyMetadata::buildType)
                .toSet()
        }

        private val leafClosures = mutableMapOf<ProjectDependencyBucket, Map<String, ResolvedDependency>>()
        private val leafUnitTestClosures = mutableMapOf<ProjectDependencyBucket, Map<String, ResolvedDependency>>()
        private val leafAndroidTestClosures = mutableMapOf<ProjectDependencyBucket, Map<String, ResolvedDependency>>()
        private val hierarchyBucketClosures = mutableMapOf<ProjectDependencyBucket, Map<String, ResolvedDependency>>()
        private val testHierarchyBucketClosures = mutableMapOf<ProjectDependencyBucket, Map<String, ResolvedDependency>>()
        private val reachableMainProjectPaths = sortedSetOf<String>()
        private val reachableMainBucketNamesByProject = sortedMapOf<String, MutableSet<String>>()
        private val mainProjectEdgeScopes = mutableListOf<MainProjectEdgeScope>()
        private var lintDeps = emptyMap<String, ResolvedDependency>()
        private var sawBinaryRoot = false

        fun resolve(): List<ResolveDependenciesResult> {
            if (migratableProjectPaths.isEmpty()) return emptyList()

            collectRootClosures()
            if (!sawBinaryRoot) {
                logger.warn("Grazel: No migratable binary modules found for aggregated dependency resolution")
                return emptyList()
            }

            val declaredTestDependenciesByBucket = addDeclaredMetadataClosures()
            if (!hasResolvedClosures()) {
                logger.warn("Grazel: No leaf variant runtime classpaths resolved")
            }

            val mainBuckets = planMainBuckets()
            val testBuckets = planTestBuckets(
                variantType = Test,
                baseBucketName = TEST_VARIANT,
                leafClosures = leafUnitTestClosures,
                mainCoveredDepsByProject = mainBuckets.mainCoveredDepsByProject,
                aggregateMainCoveredDeps = mainBuckets.aggregateMainCoveredDeps,
                declaredTestDependenciesByBucket = declaredTestDependenciesByBucket
            )
            val androidTestBuckets = planTestBuckets(
                variantType = AndroidTest,
                baseBucketName = ANDROID_TEST_VARIANT,
                leafClosures = leafAndroidTestClosures,
                mainCoveredDepsByProject = mainBuckets.mainCoveredDepsByProject,
                aggregateMainCoveredDeps = mainBuckets.aggregateMainCoveredDeps,
                declaredTestDependenciesByBucket = declaredTestDependenciesByBucket,
                inheritedTestCoveredDeps = testBuckets
                    .flatMap { (bucketName, deps) -> deps.asCoveredBy(bucketName) }
            )

            return buildResults(
                mainBuckets = mainBuckets,
                testBuckets = testBuckets,
                androidTestBuckets = androidTestBuckets
            )
        }

        private fun variantsFor(projectPath: String): List<DeclaredVariantDependencyMetadata> =
            projectMetadataByPath[projectPath]?.variants.orEmpty()

        private fun String.toDeclaredProjectDependencyEdge(): DeclaredProjectDependencyEdge? {
            parsedDeclaredProjectDependencyEdges[this]?.let { edge -> return edge }
            if (this in ignoredDeclaredProjectDependencyEdges) return null

            val edgeTarget = substringAfter("->", missingDelimiterValue = "")
            if (edgeTarget.isBlank()) {
                ignoredDeclaredProjectDependencyEdges += this
                return null
            }
            val targetProjectPath = projectPathsByDescendingLength
                .firstOrNull { projectPath -> edgeTarget.startsWith("$projectPath:") }
            if (targetProjectPath == null) {
                ignoredDeclaredProjectDependencyEdges += this
                return null
            }
            val excludedShortIds = substringAfterLast(":[", missingDelimiterValue = "")
                .removeSuffix("]")
                .split(",")
                .mapNotNull { shortId ->
                    shortId.trim().takeUnless(String::isBlank)
                }
                .toSortedSet()
            return DeclaredProjectDependencyEdge(
                targetProjectPath = targetProjectPath,
                excludedShortIds = excludedShortIds
            ).also { edge -> parsedDeclaredProjectDependencyEdges[this] = edge }
        }

        private fun declaredProjectDependencyEdges(
            projectPath: String,
            variantNames: Set<String>,
            selectedOnly: Boolean
        ): List<DeclaredProjectDependencyEdge> {
            val cacheKey = Triple(projectPath, variantNames.toSortedSet(), selectedOnly)
            declaredProjectDependencyEdgesCache[cacheKey]?.let { edges -> return edges }

            return variantsFor(projectPath)
                .asSequence()
                .filter { variant ->
                    variant.variantType == AndroidBuild || variant.variantType == JvmBuild
                }
                .filter { variant ->
                    !selectedOnly || variant.name in variantNames
                }
                .flatMap { variant -> variant.declaredProjectDependencies.asSequence() }
                .mapNotNull { edge -> edge.toDeclaredProjectDependencyEdge() }
                .toList()
                .also { edges -> declaredProjectDependencyEdgesCache[cacheKey] = edges }
        }

        private fun MutableMap<ProjectDependencyBucket, Map<String, ResolvedDependency>>.addToProjectBucket(
            projectPath: String,
            bucketName: String,
            closure: Map<String, ResolvedDependency>,
            keepEmpty: Boolean = true
        ) {
            if (!keepEmpty && closure.isEmpty()) return
            val bucket = ProjectDependencyBucket(projectPath, bucketName)
            this[bucket] = this[bucket]
                ?.let { existing -> unionDependencyMaps(existing, closure) }
                ?: closure
        }

        private fun addToHierarchyBucket(
            projectPath: String,
            bucketName: String,
            closure: Map<String, ResolvedDependency>
        ) {
            hierarchyBucketClosures.addToProjectBucket(
                projectPath,
                bucketName,
                closure,
                keepEmpty = false
            )
        }

        private fun addToTestHierarchyBucket(
            projectPath: String,
            bucketName: String,
            closure: Map<String, ResolvedDependency>
        ) {
            testHierarchyBucketClosures.addToProjectBucket(
                projectPath,
                bucketName,
                closure,
                keepEmpty = false
            )
        }

        private fun AggregatedDependencyRootMetadata.variantHierarchyNames(): Set<String> {
            return variantNames.ifEmpty {
                listOfNotNull(leafName, bucketName).toSet()
            }
        }

        private fun AggregatedDependencyRootMetadata.targetBucketNames(): Set<String> {
            return targetBuckets.ifEmpty {
                listOfNotNull(bucketName).toSet()
            }
        }

        private fun excludeRulesFor(
            metadata: AggregatedDependencyRootMetadata,
            variantType: VariantType
        ): Map<String, ProjectExcludeRules> {
            return declaredDependencyMetadata.collectExcludeRulesByProjectPath(
                variantTypes = setOf(variantType),
                variantNames = metadata.variantHierarchyNames()
            )
        }

        private fun addReachableMainBuckets(projectPath: String, bucketNames: Iterable<String>) {
            val reachableBucketNames = reachableMainBucketNamesByProject
                .getOrPut(projectPath) { sortedSetOf() }
            reachableBucketNames.addAll(bucketNames.filter(String::isNotBlank))
        }

        private fun selectedMainVariantHierarchyNames(
            projectPath: String,
            selectedVariantDisplayName: String?
        ): Set<String> {
            val variantHierarchyNamesByName = variantsFor(projectPath)
                .asSequence()
                .filter { variant ->
                    variant.variantType == AndroidBuild || variant.variantType == JvmBuild
                }
                .associate { variant -> variant.name to (setOf(variant.name) + variant.extendsFrom) }
            return selectedVariantHierarchyNames(
                displayName = selectedVariantDisplayName,
                variantHierarchyNamesByName = variantHierarchyNamesByName
            ).ifEmpty {
                setOf(DEFAULT_VARIANT).filter { bucketName -> bucketName in variantHierarchyNamesByName }.toSet()
            }
        }

        private fun knownMainBucketNames(projectPath: String, bucketNames: Set<String>): Set<String> {
            val knownBucketNames = variantsFor(projectPath)
                .asSequence()
                .filter { variant ->
                    variant.variantType == AndroidBuild || variant.variantType == JvmBuild
                }
                .map(DeclaredVariantDependencyMetadata::name)
                .toSet()
            return bucketNames
                .filter { bucketName -> bucketName in knownBucketNames }
                .toSet()
                .ifEmpty {
                    setOf(DEFAULT_VARIANT)
                        .filter { bucketName -> bucketName in knownBucketNames }
                        .toSet()
                }
        }

        private fun ProjectDependencyBucket.isReachableMainBucket(): Boolean {
            return bucketName in reachableMainBucketNamesByProject[projectPath].orEmpty()
        }

        private fun Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>.withoutDependenciesExcludedByEveryReachableRoot():
            Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
            if (mainProjectEdgeScopes.isEmpty()) return this
            val reachableScopesByProjectPath = mainProjectEdgeScopes
                .flatMap { scope ->
                    scope.reachableProjectPaths.map { projectPath -> projectPath to scope }
                }
                .groupBy(
                    keySelector = { (projectPath, _) -> projectPath },
                    valueTransform = { (_, scope) -> scope }
                )
            return mapValues { (bucket, dependencies) ->
                val reachableScopes = reachableScopesByProjectPath[bucket.projectPath].orEmpty()
                dependencies.filterKeys { shortId ->
                    reachableScopes.isEmpty() ||
                        reachableScopes.any { scope ->
                            shortId !in scope.excludedShortIdsByTargetProject[bucket.projectPath].orEmpty()
                        }
                }
            }
        }

        private fun AggregatedDependencyRootMetadata.shouldResolveMainHierarchyRoot(): Boolean {
            if (kind != AggregatedDependencyRootKind.MAIN_HIERARCHY || variantType != AndroidBuild) {
                return true
            }
            val bucket = bucketName ?: return true
            if (bucket == DEFAULT_VARIANT) return true
            return bucket in mainBuildTypeNamesByProject[projectPath].orEmpty()
        }

        private fun ProjectDependencyBucket.shouldAddDeclaredHierarchyDependency(): Boolean {
            val projectType = projectMetadataByPath[projectPath]?.projectType
                ?: DeclaredProjectType.OTHER
            return projectType == DeclaredProjectType.OTHER || bucketName != DEFAULT_VARIANT
        }

        private fun collectMainProjectEdgeScope(
            projectPath: String,
            variantNames: Set<String>,
            selectedOnly: Boolean
        ): MainProjectEdgeScope {
            val scopeReachableProjectPaths = sortedSetOf<String>()
            val scopeReachableBucketNamesByProject = sortedMapOf<String, MutableSet<String>>()
            val scopeExcludedShortIdsByTargetProject = sortedMapOf<String, MutableSet<String>>()

            fun addScopedReachableBuckets(
                currentProjectPath: String,
                bucketNames: Set<String>
            ) {
                val knownBucketNames = knownMainBucketNames(currentProjectPath, bucketNames)
                if (knownBucketNames.isEmpty()) return
                scopeReachableBucketNamesByProject
                    .getOrPut(currentProjectPath) { sortedSetOf() }
                    .addAll(knownBucketNames)
            }

            fun visit(
                currentProjectPath: String,
                selectedOnlyForProject: Boolean
            ) {
                if (!scopeReachableProjectPaths.add(currentProjectPath)) return

                val dependencyEdges = declaredProjectDependencyEdges(
                    projectPath = currentProjectPath,
                    variantNames = variantNames,
                    selectedOnly = selectedOnlyForProject
                )
                dependencyEdges.forEach { edge ->
                    if (edge.excludedShortIds.isNotEmpty()) {
                        scopeExcludedShortIdsByTargetProject
                            .getOrPut(edge.targetProjectPath) { sortedSetOf() }
                            .addAll(edge.excludedShortIds)
                    }
                }
                dependencyEdges.forEach { edge ->
                    addScopedReachableBuckets(edge.targetProjectPath, variantNames)
                    visit(
                        currentProjectPath = edge.targetProjectPath,
                        selectedOnlyForProject = selectedOnlyForProject
                    )
                }
            }

            visit(projectPath, selectedOnly)
            return MainProjectEdgeScope(
                reachableProjectPaths = scopeReachableProjectPaths,
                reachableBucketNamesByProject = scopeReachableBucketNamesByProject
                    .mapValues { (_, bucketNames) -> bucketNames.toSortedSet() }
                    .toSortedMap(),
                excludedShortIdsByTargetProject = scopeExcludedShortIdsByTargetProject
                    .mapValues { (_, shortIds) -> shortIds.toSortedSet() }
                    .toSortedMap()
            )
        }

        private fun collectRootClosures() {
            workspaceDependencyRoots.forEach { aggregatedRoot ->
                val metadata = aggregatedRoot.metadata
                if (!metadata.shouldResolveMainHierarchyRoot()) {
                    return@forEach
                }
                var mainProjectEdgeScope: MainProjectEdgeScope? = null
                val reachableProjectPaths = when (metadata.kind) {
                    AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    AggregatedDependencyRootKind.MAIN_LEAF,
                    AggregatedDependencyRootKind.TEST_HIERARCHY,
                    AggregatedDependencyRootKind.UNIT_TEST,
                    AggregatedDependencyRootKind.ANDROID_TEST -> reachableMainProjectPaths
                    else -> null
                }
                when (metadata.kind) {
                    AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    AggregatedDependencyRootKind.MAIN_LEAF -> {
                        val scope = collectMainProjectEdgeScope(
                            projectPath = metadata.projectPath,
                            variantNames = metadata.variantHierarchyNames(),
                            selectedOnly = true
                        )
                        mainProjectEdgeScopes.add(scope)
                        reachableMainProjectPaths.addAll(scope.reachableProjectPaths)
                        scope.reachableBucketNamesByProject.forEach { (projectPath, bucketNames) ->
                            addReachableMainBuckets(projectPath, bucketNames)
                        }
                        mainProjectEdgeScope = scope
                    }
                    else -> Unit
                }
                when (metadata.kind) {
                    AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    AggregatedDependencyRootKind.MAIN_LEAF -> addReachableMainBuckets(
                        projectPath = metadata.projectPath,
                        bucketNames = metadata.variantHierarchyNames()
                    )
                    else -> Unit
                }
                val closure = when (metadata.kind) {
                    AggregatedDependencyRootKind.LINT -> resolveRootToDependencyMap(aggregatedRoot)
                    AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    AggregatedDependencyRootKind.MAIN_LEAF -> resolveRootToDependencyMap(
                        aggregatedRoot = aggregatedRoot,
                        excludeRulesByProjectPath = excludeRulesFor(metadata, AndroidBuild),
                        reachableProjectPaths = reachableProjectPaths,
                        reachableBucketNamesByProject = reachableMainBucketNamesByProject,
                        projectEdgeExcludedShortIdsByTargetProject = mainProjectEdgeScope
                            ?.excludedShortIdsByTargetProject.orEmpty(),
                        reachableBucketNamesForProject = ::selectedMainVariantHierarchyNames
                    )
                    AggregatedDependencyRootKind.TEST_HIERARCHY -> resolveRootToDependencyMap(
                        aggregatedRoot = aggregatedRoot,
                        excludeRulesByProjectPath = excludeRulesFor(
                            metadata = metadata,
                            variantType = metadata.variantType ?: when (metadata.bucketName) {
                                ANDROID_TEST_VARIANT -> AndroidTest
                                else -> Test
                            }
                        ),
                        reachableProjectPaths = reachableProjectPaths,
                        reachableBucketNamesByProject = reachableMainBucketNamesByProject,
                        reachableBucketNamesForProject = ::selectedMainVariantHierarchyNames
                    )
                    AggregatedDependencyRootKind.UNIT_TEST -> {
                        val leafHierarchyNames = metadata.variantHierarchyNames()
                        resolveRootToDependencyMap(
                            aggregatedRoot = aggregatedRoot,
                            excludeRulesByProjectPath = declaredDependencyMetadata
                                .collectExcludeRulesByProjectPath(
                                    variantTypes = setOf(Test),
                                    variantNames = variantNamesForLeafTest(
                                        variants = variantsFor(metadata.projectPath),
                                        leafHierarchyNames = leafHierarchyNames,
                                        testType = Test
                                    )
                                ),
                            reachableProjectPaths = reachableProjectPaths,
                            reachableBucketNamesByProject = reachableMainBucketNamesByProject,
                            reachableBucketNamesForProject = ::selectedMainVariantHierarchyNames
                        )
                    }
                    AggregatedDependencyRootKind.ANDROID_TEST -> {
                        val leafHierarchyNames = metadata.variantHierarchyNames()
                        resolveRootToDependencyMap(
                            aggregatedRoot = aggregatedRoot,
                            excludeRulesByProjectPath = declaredDependencyMetadata
                                .collectExcludeRulesByProjectPath(
                                    variantTypes = setOf(AndroidTest),
                                    variantNames = variantNamesForLeafTest(
                                        variants = variantsFor(metadata.projectPath),
                                        leafHierarchyNames = leafHierarchyNames,
                                        testType = AndroidTest
                                    )
                                ),
                            reachableProjectPaths = reachableProjectPaths,
                            reachableBucketNamesByProject = reachableMainBucketNamesByProject,
                            reachableBucketNamesForProject = ::selectedMainVariantHierarchyNames
                        )
                    }
                }

                when (metadata.kind) {
                    AggregatedDependencyRootKind.MAIN_HIERARCHY -> {
                        sawBinaryRoot = true
                        metadata.targetBucketNames().forEach { bucketName ->
                            when (bucketName) {
                                TEST_VARIANT, ANDROID_TEST_VARIANT ->
                                    addToTestHierarchyBucket(metadata.projectPath, bucketName, closure)
                                else -> addToHierarchyBucket(metadata.projectPath, bucketName, closure)
                            }
                        }
                    }
                    AggregatedDependencyRootKind.MAIN_LEAF -> {
                        sawBinaryRoot = true
                        val leafName = metadata.leafName ?: metadata.bucketName ?: return@forEach
                        val targetBuckets = metadata.targetBucketNames()
                        targetBuckets.forEach { bucketName ->
                            when (bucketName) {
                                DEFAULT_VARIANT -> addToHierarchyBucket(metadata.projectPath, DEFAULT_VARIANT, closure)
                                TEST_VARIANT ->
                                    leafUnitTestClosures.addToProjectBucket(metadata.projectPath, leafName, closure)
                                ANDROID_TEST_VARIANT ->
                                    leafAndroidTestClosures.addToProjectBucket(metadata.projectPath, leafName, closure)
                                else -> {
                                    leafClosures.addToProjectBucket(metadata.projectPath, bucketName, closure)
                                }
                            }
                        }
                    }
                    AggregatedDependencyRootKind.TEST_HIERARCHY -> {
                        metadata.targetBucketNames().forEach { bucketName ->
                            addToTestHierarchyBucket(metadata.projectPath, bucketName, closure)
                        }
                    }
                    AggregatedDependencyRootKind.UNIT_TEST -> {
                        val leafName = metadata.leafName ?: metadata.bucketName ?: return@forEach
                        leafUnitTestClosures.addToProjectBucket(metadata.projectPath, leafName, closure)
                    }
                    AggregatedDependencyRootKind.ANDROID_TEST -> {
                        val leafName = metadata.leafName ?: metadata.bucketName ?: return@forEach
                        leafAndroidTestClosures.addToProjectBucket(metadata.projectPath, leafName, closure)
                    }
                    AggregatedDependencyRootKind.LINT -> {
                        lintDeps = unionDependencyMaps(lintDeps, closure)
                    }
                }
            }
        }

        private fun addDeclaredMetadataClosures(): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
            val compileOnlyDependenciesByBucket = declaredDependencyMetadata
                .collectCompileOnlyDependenciesByProjectBucket(projectMetadataByPath.keys)
            val declaredMainDependenciesByBucket = declaredDependencyMetadata
                .collectDeclaredMainDependenciesByProjectBucket(projectMetadataByPath.keys)

            compileOnlyDependenciesByBucket
                .filter { (bucket, _) -> bucket.shouldAddDeclaredHierarchyDependency() }
                .forEach { (bucket, dependencies) ->
                    when (bucket.bucketName) {
                        TEST_VARIANT, ANDROID_TEST_VARIANT ->
                            addToTestHierarchyBucket(bucket.projectPath, bucket.bucketName, dependencies)
                        else -> addToHierarchyBucket(bucket.projectPath, bucket.bucketName, dependencies)
                    }
                }

            declaredMainDependenciesByBucket
                .filter { (bucket, _) -> bucket.shouldAddDeclaredHierarchyDependency() }
                .filter { (bucket, _) -> bucket.isReachableMainBucket() }
                .withoutDependenciesExcludedByEveryReachableRoot()
                .filterValues(Map<String, ResolvedDependency>::isNotEmpty)
                .forEach { (bucket, dependencies) ->
                    addToHierarchyBucket(bucket.projectPath, bucket.bucketName, dependencies)
                }

            return declaredDependencyMetadata
                .collectDeclaredTestDependenciesByProjectBucket(projectMetadataByPath.keys)
                .also { declaredTestDependenciesByBucket ->
                    declaredTestDependenciesByBucket.forEach { (bucket, dependencies) ->
                        addToTestHierarchyBucket(bucket.projectPath, bucket.bucketName, dependencies)
                    }
                }
        }

        private fun hasResolvedClosures(): Boolean {
            return leafClosures.isNotEmpty() ||
                hierarchyBucketClosures.isNotEmpty() ||
                leafUnitTestClosures.isNotEmpty() ||
                leafAndroidTestClosures.isNotEmpty() ||
                testHierarchyBucketClosures.isNotEmpty()
        }

        private fun planMainBuckets(): MainBucketPlanResult {
            val mainBucketVariants = declaredDependencyMetadata.mainBucketVariantsByProject()
            val declaredMainDependenciesByBucket = declaredDependencyMetadata
                .collectDeclaredMainDependenciesByProjectBucket(projectMetadataByPath.keys)
            val mainBucketPlansByProject = DependencyBucketPlacementEngine().planByProject(
                variants = mainBucketVariants,
                hierarchyBucketClosures = hierarchyBucketClosures,
                leafClosures = leafClosures
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
            variantType: VariantType,
            baseBucketName: String
        ): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
            val testSuffix = variantType.testSuffix
            return testHierarchyBucketClosures.filterKeys { bucket ->
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
            mainBuckets: MainBucketPlanResult,
            testBuckets: Map<String, Map<String, ResolvedDependency>>,
            androidTestBuckets: Map<String, Map<String, ResolvedDependency>>
        ): List<ResolveDependenciesResult> {
            val results = mutableListOf<ResolveDependenciesResult>()
            val reachableMainBucketsSnapshot = reachableMainBucketNamesByProject
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
            if (lintDeps.isNotEmpty()) results.add(buildResult("lint", lintDeps))

            return results
        }
    }

    private data class MainBucketPlanResult(
        val defaultDeps: Map<String, ResolvedDependency>,
        val hierarchyBuckets: Map<String, Map<String, ResolvedDependency>>,
        val filteredPerLeafBuckets: Map<String, Map<String, ResolvedDependency>>,
        val mainCoveredDepsByProject: Map<String, List<CoveredDependency>>,
        val aggregateMainCoveredDeps: List<CoveredDependency>
    )

    private fun variantNamesForLeafTest(
        variants: Collection<DeclaredVariantDependencyMetadata>,
        leafHierarchyNames: Set<String>,
        testType: VariantType
    ): Set<String> {
        val relevantTestVariantNames = variants
            .asSequence()
            .filter { variant -> variant.variantType == testType }
            .filter { variant ->
                variant.name in leafHierarchyNames ||
                    variant.extendsFrom.any { it in leafHierarchyNames }
            }
            .flatMap { variant -> (setOf(variant.name) + variant.extendsFrom).asSequence() }
            .toSet()
        return leafHierarchyNames + relevantTestVariantNames
    }

    /**
     * Union two dependency maps, preferring the higher version on shortId conflicts.
     * This merges RuntimeClasspath and CompileClasspath results so that compileOnly
     * and api-of-consumed-libs entries (present only in CompileClasspath) are included.
     */
    private fun unionDependencyMaps(
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

    private fun resolveRootToDependencyMap(
        aggregatedRoot: AggregatedDependencyRoot,
        excludeRulesByProjectPath: Map<String, ProjectExcludeRules> = emptyMap(),
        reachableProjectPaths: MutableSet<String>? = null,
        reachableBucketNamesByProject: MutableMap<String, MutableSet<String>>? = null,
        projectEdgeExcludedShortIdsByTargetProject: Map<String, Set<String>> = emptyMap(),
        reachableBucketNamesForProject: ((String, String?) -> Set<String>)? = null
    ): Map<String, ResolvedDependency> {
        val metadata = aggregatedRoot.metadata
        return try {
            val depMap = mutableMapOf<String, ResolvedDependency>()
            val visitResults = mutableListOf<ResolvedComponentsVisitor.VisitResult>()
            ResolvedComponentsVisitor().visit(
                root = aggregatedRoot.root,
                traverseProjectNodes = metadata.traverseProjectNodes
            ) { visitResult ->
                val component = visitResult.component
                val moduleVersion = component.moduleVersion ?: return@visit null
                val shortId = "${moduleVersion.group}:${moduleVersion.name}"
                // Skip BOM/platform (pom-only) components — rules_jvm_external rejects them with
                // "Unsupported packaging type: pom". BOM components appear in the resolution graph,
                // but have no actual jar/aar artifact.
                if (component.isBomComponent()) {
                    return@visit null
                }
                if (metadata.traverseProjectNodes && !visitResult.directFromProject) {
                    return@visit null
                }
                visitResults.add(visitResult)
                shortId
            }
            visitResults
                .sortedWith(
                    compareBy<ResolvedComponentsVisitor.VisitResult> { visitResult ->
                        visitResult.component.toString()
                    }
                        .thenBy { visitResult -> visitResult.repository }
                        .thenBy { visitResult -> visitResult.directProjectPath }
                        .thenBy { visitResult -> visitResult.directProjectVariantDisplayName }
                        .thenBy { visitResult -> visitResult.directFromProject }
                        .thenBy { visitResult -> visitResult.requiresJetifier }
                )
                .forEach { visitResult ->
                    visitResult.directProjectPath?.let { projectPath ->
                        reachableProjectPaths?.add(projectPath)
                        val reachableBucketNames = reachableBucketNamesForProject
                            ?.invoke(projectPath, visitResult.directProjectVariantDisplayName)
                            .orEmpty()
                        reachableBucketNamesByProject
                            ?.getOrPut(projectPath) { sortedSetOf() }
                            ?.addAll(reachableBucketNames)
                    }
                    val component = visitResult.component
                    val moduleVersion = component.moduleVersion ?: return@forEach
                    val shortId = "${moduleVersion.group}:${moduleVersion.name}"
                    val excludedByProjectEdge = visitResult.directProjectPath
                        ?.let { projectPath ->
                            shortId in projectEdgeExcludedShortIdsByTargetProject[projectPath].orEmpty()
                        }
                        ?: false
                    if (excludedByProjectEdge) {
                        return@forEach
                    }
                    val excludeRules = if (metadata.traverseProjectNodes) {
                        excludeRulesByProjectPath.excludeRulesFor(
                            rootProjectPath = metadata.projectPath,
                            rootExcludeRulesByShortId = metadata.rootExcludeRulesByShortId,
                            ownerProjectPath = visitResult.directProjectPath,
                            ownerProjectVariantDisplayName = visitResult.directProjectVariantDisplayName,
                            shortId = shortId
                        )
                    } else {
                        metadata.rootExcludeRulesByShortId.getOrDefault(shortId, emptySet())
                    }
                    val dependency = ResolvedDependency(
                        id = component.toString(),
                        shortId = shortId,
                        version = moduleVersion.version,
                        direct = if (metadata.traverseProjectNodes) {
                            true
                        } else {
                            shortId in metadata.directDependencyShortIds
                        },
                        dependencies = visitResult.transitiveDeps
                            .filterNot { dependencyResult ->
                                dependencyResult.dependency.isBomComponent()
                            }
                            .mapTo(TreeSet()) { dependencyResult ->
                                ResolvedDependency.createDependencyNotation(
                                    dependencyResult.dependency,
                                    dependencyResult.requiresJetifier,
                                    dependencyResult.unjetifiedSource
                                )
                            },
                        excludeRules = excludeRules,
                        repository = visitResult.repository,
                        requiresJetifier = visitResult.requiresJetifier
                    )
                    val existingDependency = depMap[shortId]
                    val mergedDependency = if (existingDependency == null) {
                        dependency
                    } else {
                        mergeDependencyMetadataByMaxVersion(existingDependency, dependency)
                    }
                    depMap[shortId] = mergedDependency
                }
            depMap
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to resolve aggregated root ${metadata.configurationName} " +
                    "for ${metadata.projectPath}",
                e
            )
        }
    }

}

internal fun mergeDependencyMetadataByMaxVersion(
    existing: ResolvedDependency,
    candidate: ResolvedDependency
): ResolvedDependency {
    val exactlyOneSideIsDeclaredMetadata = existing.isDeclaredMetadata() xor candidate.isDeclaredMetadata()
    val winner = when {
        existing.isDeclaredMetadata() && !candidate.isDeclaredMetadata() -> candidate
        candidate.isDeclaredMetadata() && !existing.isDeclaredMetadata() -> existing
        existing.versionInfo > candidate.versionInfo -> existing
        candidate.versionInfo > existing.versionInfo -> candidate
        else -> candidate
    }
    val other = if (winner === existing) candidate else existing
    return winner.copy(
        direct = winner.direct || other.direct,
        dependencies = when (winner.version) {
            other.version -> (winner.dependencies + other.dependencies).toSortedSet()
            else -> winner.dependencies
        },
        excludeRules = if (exactlyOneSideIsDeclaredMetadata) {
            (winner.excludeRules + other.excludeRules).toSortedSet(compareBy(ExcludeRule::toString))
        } else {
            winner.excludeRules.intersectWith(other.excludeRules)
        },
        requiresJetifier = winner.requiresJetifier || other.requiresJetifier,
        jetifierSource = winner.jetifierSource ?: other.jetifierSource,
        overrideTarget = winner.overrideTarget ?: other.overrideTarget,
        processorClass = winner.processorClass ?: other.processorClass,
    )
}

internal fun ResolvedDependency.isDeclaredMetadata(): Boolean =
    repository == DECLARED_DEPENDENCY_REPOSITORY

private data class DeclaredProjectDependencyEdge(
    val targetProjectPath: String,
    val excludedShortIds: Set<String>
)

private data class MainProjectEdgeScope(
    val reachableProjectPaths: Set<String>,
    val reachableBucketNamesByProject: Map<String, Set<String>>,
    val excludedShortIdsByTargetProject: Map<String, Set<String>>
)

internal data class CoveredDependency(
    val bucketName: String,
    val dependency: ResolvedDependency
)

internal fun Map<String, ResolvedDependency>.withoutDependenciesCoveredBy(
    coveredDependencies: Iterable<CoveredDependency>
): Map<String, ResolvedDependency> =
    withoutDependenciesCoveredBy(coveredDependencies.groupByShortId())

private fun Iterable<CoveredDependency>.groupByShortId(): Map<String, List<CoveredDependency>> =
    groupBy { it.dependency.shortId }

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

internal fun Map<String, ResolvedDependency>.withoutDependenciesOwnedByNonDefaultHierarchy(
    hierarchyDefaultDeps: Map<String, ResolvedDependency>,
    nonDefaultHierarchyDependencies: Iterable<ResolvedDependency>
): Map<String, ResolvedDependency> {
    val nonDefaultByShortId = nonDefaultHierarchyDependencies.groupBy { it.shortId }
    return filterValues { dependency ->
        val hasSameNonDefaultOwner = nonDefaultByShortId[dependency.shortId]
            .orEmpty()
            .any { nonDefaultDependency ->
                nonDefaultDependency.hasSameResolvedOwnerIdentityAs(dependency)
            }
        !hasSameNonDefaultOwner ||
            hierarchyDefaultDeps[dependency.shortId]?.hasSameResolvedOwnerIdentityAs(dependency) == true
    }
}

internal fun intersectByBucketOwner(
    closures: Iterable<Map<String, ResolvedDependency>>
): Map<String, ResolvedDependency> {
    val closureList = closures.toList()
    if (closureList.isEmpty()) return emptyMap()
    val intersectionIds = closureList
        .map { it.keys }
        .reduce { a, b -> a intersect b }
    val firstClosure = closureList.first()
    return intersectionIds.mapNotNull { id ->
        val candidate = firstClosure[id]!!
        if (closureList.all { closure -> closure[id]?.hasSameResolvedOwnerIdentityAs(candidate) == true }) {
            id to candidate
        } else {
            null
        }
    }.toMap()
}

private fun CoveredDependency.toOverrideTarget(): OverrideTarget {
    return mavenOverrideTarget(dependency.shortId, bucketName)
}
