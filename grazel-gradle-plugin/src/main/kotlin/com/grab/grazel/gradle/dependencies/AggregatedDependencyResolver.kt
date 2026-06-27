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

            return BucketOwnershipPlanner(
                declaredDependencyMetadata = declaredDependencyMetadata,
                precomputedKspDependencies = precomputedKspDependencies
            ).plan(
                OwnershipPlannerInput(
                    leafClosures = leafClosures.snapshotDependencyBuckets(),
                    leafUnitTestClosures = leafUnitTestClosures.snapshotDependencyBuckets(),
                    leafAndroidTestClosures = leafAndroidTestClosures.snapshotDependencyBuckets(),
                    hierarchyBucketClosures = hierarchyBucketClosures.snapshotDependencyBuckets(),
                    testHierarchyBucketClosures = testHierarchyBucketClosures.snapshotDependencyBuckets(),
                    reachableMainBucketNamesByProject = reachableMainBucketNamesByProject
                        .mapValues { (_, bucketNames) -> bucketNames.toSortedSet() }
                        .toSortedMap(),
                    lintDeps = lintDeps.toSortedMap(),
                    declaredTestDependenciesByBucket = declaredTestDependenciesByBucket.snapshotDependencyBuckets()
                )
            )
        }

        private fun Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>.snapshotDependencyBuckets():
            Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
            return toSortedMap(
                compareBy<ProjectDependencyBucket> { bucket -> bucket.projectPath }
                    .thenBy { bucket -> bucket.bucketName }
            ).mapValues { (_, dependencies) -> dependencies.toSortedMap() }
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

    }

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
