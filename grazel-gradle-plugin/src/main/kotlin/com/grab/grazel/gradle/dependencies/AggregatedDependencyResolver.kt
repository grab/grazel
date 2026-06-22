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
import com.grab.grazel.gradle.dependencies.model.hasSameBucketOwnerAs
import com.grab.grazel.gradle.dependencies.model.hasSameEffectiveIdentityAs
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
    fun resolve(): List<ResolveDependenciesResult> {
        val projectMetadataByPath = declaredDependencyMetadata.projects
        val migratableProjectPaths = projectMetadataByPath.keys.sorted()
        val projectPathsByDescendingLength = migratableProjectPaths.sortedByDescending(String::length)

        if (migratableProjectPaths.isEmpty()) return emptyList()

        fun variantsFor(projectPath: String): List<DeclaredVariantDependencyMetadata> =
            projectMetadataByPath[projectPath]?.variants.orEmpty()

        fun String.toDeclaredProjectDependencyEdge(): DeclaredProjectDependencyEdge? {
            val edgeTarget = substringAfter("->", missingDelimiterValue = "")
            if (edgeTarget.isBlank()) return null
            val targetProjectPath = projectPathsByDescendingLength
                .firstOrNull { projectPath -> edgeTarget.startsWith("$projectPath:") }
                ?: return null
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
            )
        }

        fun declaredProjectDependencyEdges(
            projectPath: String,
            variantNames: Set<String>,
            selectedOnly: Boolean
        ): List<DeclaredProjectDependencyEdge> {
            return variantsFor(projectPath)
                .asSequence()
                .filter { variant ->
                    variant.variantType == AndroidBuild || variant.variantType == JvmBuild
                }
                .filter { variant ->
                    !selectedOnly ||
                        variant.name in variantNames ||
                        variant.extendsFrom.any { parent -> parent in variantNames }
                }
                .flatMap { variant -> variant.declaredProjectDependencies.asSequence() }
                .mapNotNull { edge -> edge.toDeclaredProjectDependencyEdge() }
                .toList()
        }

        val mainBuildTypeNamesByProject = migratableProjectPaths.associateWith { projectPath ->
            variantsFor(projectPath)
                .asSequence()
                .filter { variant -> variant.variantType == AndroidBuild }
                .filter(DeclaredVariantDependencyMetadata::androidLeafVariant)
                .mapNotNull(DeclaredVariantDependencyMetadata::buildType)
                .toSet()
        }

        // Per-main-bucket closure maps keyed by the binary/project topology that produced them.
        // Final maven repos are still global names; project keys prevent unrelated topologies with
        // the same bucket name from being planned as one graph.
        val leafClosures = mutableMapOf<ProjectDependencyBucket, Map<String, ResolvedDependency>>()
        val leafUnitTestClosures = mutableMapOf<ProjectDependencyBucket, Map<String, ResolvedDependency>>()
        val leafAndroidTestClosures = mutableMapOf<ProjectDependencyBucket, Map<String, ResolvedDependency>>()
        // Synthetic hierarchy buckets such as default/debug/free, resolved from their own Gradle
        // declaration buckets. These prevent filtered leaves from making debug/flavor deps look
        // like default deps.
        val hierarchyBucketClosures = mutableMapOf<ProjectDependencyBucket, Map<String, ResolvedDependency>>()
        val testHierarchyBucketClosures = mutableMapOf<ProjectDependencyBucket, Map<String, ResolvedDependency>>()
        val reachableMainProjectPaths = sortedSetOf<String>()
        val reachableMainBucketNamesByProject = sortedMapOf<String, MutableSet<String>>()
        val mainProjectEdgeScopes = mutableListOf<MainProjectEdgeScope>()

        // Lint deps across all migratable projects
        var lintDeps = emptyMap<String, ResolvedDependency>()

        fun MutableMap<ProjectDependencyBucket, Map<String, ResolvedDependency>>.addToProjectBucket(
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

        fun addToHierarchyBucket(
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

        fun addToTestHierarchyBucket(
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

        fun AggregatedDependencyRootMetadata.variantHierarchyNames(): Set<String> {
            return variantNames.ifEmpty {
                listOfNotNull(leafName, bucketName).toSet()
            }
        }

        fun AggregatedDependencyRootMetadata.targetBucketNames(): Set<String> {
            return targetBuckets.ifEmpty {
                listOfNotNull(bucketName).toSet()
            }
        }

        fun excludeRulesFor(
            metadata: AggregatedDependencyRootMetadata,
            variantType: VariantType
        ): Map<String, ProjectExcludeRules> {
            return declaredDependencyMetadata.collectExcludeRulesByProjectPath(
                variantTypes = setOf(variantType),
                variantNames = metadata.variantHierarchyNames()
            )
        }

        fun addReachableMainBuckets(projectPath: String, bucketNames: Iterable<String>) {
            val reachableBucketNames = reachableMainBucketNamesByProject
                .getOrPut(projectPath) { sortedSetOf() }
            reachableBucketNames.addAll(bucketNames.filter(String::isNotBlank))
        }

        fun selectedMainVariantHierarchyNames(
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

        fun knownMainBucketNames(projectPath: String, bucketNames: Set<String>): Set<String> {
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

        fun ProjectDependencyBucket.isReachableMainBucket(): Boolean {
            return bucketName in reachableMainBucketNamesByProject[projectPath].orEmpty()
        }

        fun Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>.withoutDependenciesExcludedByEveryReachableRoot():
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

        fun AggregatedDependencyRootMetadata.shouldResolveMainHierarchyRoot(): Boolean {
            if (kind != AggregatedDependencyRootKind.MAIN_HIERARCHY || variantType != AndroidBuild) {
                return true
            }
            val bucket = bucketName ?: return true
            if (bucket == DEFAULT_VARIANT) return true
            return bucket in mainBuildTypeNamesByProject[projectPath].orEmpty()
        }

        fun ProjectDependencyBucket.shouldAddDeclaredHierarchyDependency(): Boolean {
            val projectType = projectMetadataByPath[projectPath]?.projectType
                ?: DeclaredProjectType.OTHER
            return projectType == DeclaredProjectType.OTHER || bucketName != DEFAULT_VARIANT
        }

        var sawBinaryRoot = false

        fun collectMainProjectEdgeScope(
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
                if (!scopeReachableProjectPaths.add(currentProjectPath)) return
                dependencyEdges.forEach { edge ->
                    addScopedReachableBuckets(edge.targetProjectPath, variantNames)
                    visit(
                        currentProjectPath = edge.targetProjectPath,
                        selectedOnlyForProject = false
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

        workspaceDependencyRoots.forEach { aggregatedRoot ->
            val metadata = aggregatedRoot.metadata
            if (!metadata.shouldResolveMainHierarchyRoot()) {
                return@forEach
            }
            var mainProjectEdgeScope: MainProjectEdgeScope? = null
            val reachableProjectPaths = when (metadata.kind) {
                AggregatedDependencyRootKind.MAIN_HIERARCHY,
                AggregatedDependencyRootKind.MAIN_LEAF -> reachableMainProjectPaths
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
                    )
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
                            )
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
                            )
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
                            TEST_VARIANT, ANDROID_TEST_VARIANT ->
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

        if (!sawBinaryRoot) {
            logger.warn("Grazel: No migratable binary modules found for aggregated dependency resolution")
            return emptyList()
        }
        // compileOnly declarations are cheap metadata, not binary-root classpath edges. Add
        // non-default app/test buckets here because flavor hierarchy roots are intentionally not
        // resolved; default app/test buckets still come from the real binary classpath root.
        declaredDependencyMetadata
            .collectCompileOnlyDependenciesByProjectBucket(
                projectPaths = projectMetadataByPath.keys
            )
            .filter { (bucket, _) -> bucket.shouldAddDeclaredHierarchyDependency() }
            .forEach { (bucket, dependencies) ->
                when (bucket.bucketName) {
                    TEST_VARIANT, ANDROID_TEST_VARIANT ->
                        addToTestHierarchyBucket(bucket.projectPath, bucket.bucketName, dependencies)
                    else -> addToHierarchyBucket(bucket.projectPath, bucket.bucketName, dependencies)
                }
            }

        // implementation/api declarations are also cheap metadata. Generated targets need labels
        // for their direct declarations even when the target is outside the selected binary-root
        // graph; resolved roots still provide authoritative transitive closure for reachable app
        // and test builds.
        declaredDependencyMetadata
            .collectDeclaredMainDependenciesByProjectBucket(
                projectPaths = projectMetadataByPath.keys
            )
            .filter { (bucket, _) -> bucket.shouldAddDeclaredHierarchyDependency() }
            .filter { (bucket, _) -> bucket.isReachableMainBucket() }
            .withoutDependenciesExcludedByEveryReachableRoot()
            .filterValues(Map<String, ResolvedDependency>::isNotEmpty)
            .forEach { (bucket, dependencies) ->
                addToHierarchyBucket(bucket.projectPath, bucket.bucketName, dependencies)
            }

        // Unit-test/androidTest declarations on library modules are often not reachable from the
        // app binary root classpath. Keep them cheap and broad for this slice so generated test
        // targets can at least resolve their direct declared artifacts.
        declaredDependencyMetadata
            .collectDeclaredTestDependenciesByProjectBucket(projectMetadataByPath.keys)
            .forEach { (bucket, dependencies) ->
                addToTestHierarchyBucket(bucket.projectPath, bucket.bucketName, dependencies)
            }

        if (
            leafClosures.isEmpty() &&
            hierarchyBucketClosures.isEmpty() &&
            leafUnitTestClosures.isEmpty() &&
            leafAndroidTestClosures.isEmpty() &&
            testHierarchyBucketClosures.isEmpty()
        ) {
            logger.warn("Grazel: No leaf variant runtime classpaths resolved")
            if (!sawBinaryRoot) {
                return emptyList()
            }
        }

        val mainBucketVariants = declaredDependencyMetadata.mainBucketVariantsByProject()
        val mainBucketPlansByProject = DependencyBucketPlacementEngine().planByProject(
            variants = mainBucketVariants,
            hierarchyBucketClosures = hierarchyBucketClosures,
            leafClosures = leafClosures
        )
        val mainBucketPlans = mainBucketPlansByProject.values

        fun mergeDependencyMaps(
            dependencyMaps: Iterable<Map<String, ResolvedDependency>>
        ): Map<String, ResolvedDependency> {
            return dependencyMaps.fold(emptyMap()) { merged, dependencies ->
                unionDependencyMaps(merged, dependencies)
            }
        }

        fun mergeNamedBuckets(
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

        val defaultDeps = mergeDependencyMaps(mainBucketPlans.map(DependencyBucketPlacementPlan::defaultBucket))
        val hierarchyBuckets = mergeNamedBuckets(mainBucketPlans.map(DependencyBucketPlacementPlan::hierarchyBuckets))
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

        fun withGlobalAncestorResolvedMetadata(
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
        )

        fun testHierarchyBucketClosuresFor(
            variantType: VariantType,
            baseBucketName: String
        ): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
            val testSuffix = variantType.testSuffix
            return testHierarchyBucketClosures.filterKeys { bucket ->
                bucket.bucketName == baseBucketName || bucket.bucketName.endsWith(testSuffix)
            }
        }

        fun concreteTestLeafName(
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

        fun concreteTestLeafClosures(
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

        fun testBucketPlans(
            variantType: VariantType,
            baseBucketName: String,
            leafClosures: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
        ): Map<String, DependencyBucketPlacementPlan> {
            return DependencyBucketPlacementEngine().planByProject(
                variants = declaredDependencyMetadata.testBucketVariantsByProject(
                    variantType = variantType,
                    baseBucketName = baseBucketName
                ),
                hierarchyBucketClosures = testHierarchyBucketClosuresFor(
                    variantType = variantType,
                    baseBucketName = baseBucketName
                ),
                leafClosures = concreteTestLeafClosures(
                    leafClosures = leafClosures,
                    variantType = variantType,
                    baseBucketName = baseBucketName
                ),
                baseBucketName = baseBucketName
            )
        }

        fun Map<String, DependencyBucketPlacementPlan>.allTestDeps(): Map<String, ResolvedDependency> {
            return mergeDependencyMaps(
                map { (projectPath, plan) ->
                    mergeDependencyMaps(
                        listOf(plan.defaultBucket) +
                            plan.hierarchyBuckets.values +
                            plan.leafBuckets.values
                    ).withoutDependenciesCoveredBy(
                        mainCoveredDepsByProject[projectPath].orEmpty()
                    )
                }
            )
        }

        // Test buckets are intentionally broad for this slice. The planner can still model
        // test hierarchy internally, but emitting concrete test leaf repos creates unreferenced
        // WORKSPACE/lockfile churn in large app repos when no generated target consumes them.
        // Keep unique test artifacts in the base repos until a failing case justifies precise
        // per-test-leaf bucket emission.
        val unitTestBucketPlans = testBucketPlans(Test, TEST_VARIANT, leafUnitTestClosures)
        val testDeps = unitTestBucketPlans.allTestDeps()

        // AndroidTest follows the same broad contract, rooted at androidTest instead of test.
        val androidTestBucketPlans = testBucketPlans(AndroidTest, ANDROID_TEST_VARIANT, leafAndroidTestClosures)
        val androidTestDeps = androidTestBucketPlans.allTestDeps()

        // Build the ResolveDependenciesResult list
        val results = mutableListOf<ResolveDependenciesResult>()
        val reachableMainBucketsSnapshot = reachableMainBucketNamesByProject
            .mapValues { (_, bucketNames) -> bucketNames.toSortedSet() }

        fun buildResult(
            bucketName: String,
            deps: Map<String, ResolvedDependency>
        ): ResolveDependenciesResult {
            val kspDeps = if (bucketName == "default") precomputedKspDependencies else emptySet()
            return ResolveDependenciesResult(
                variantName = bucketName,
                dependencies = mapOf(
                    COMPILE.name to deps.values.toSortedSet(),
                    KSP.name to kspDeps
                ),
                reachableMainBucketsByProject = reachableMainBucketsSnapshot
            )
        }

        // Default bucket (required — ComputeWorkspaceDependencies.computeInternal calls getValue(DEFAULT_VARIANT))
        results.add(buildResult("default", defaultDeps))

        hierarchyBuckets.forEach { (bucketName, deps) -> results.add(buildResult(bucketName, deps)) }
        filteredPerLeafBuckets.forEach { (leaf, deps) -> results.add(buildResult(leaf, deps)) }
        if (testDeps.isNotEmpty()) results.add(buildResult("test", testDeps))
        if (androidTestDeps.isNotEmpty()) results.add(buildResult("androidTest", androidTestDeps))
        if (lintDeps.isNotEmpty()) results.add(buildResult("lint", lintDeps))

        return results
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
        excludeRules = (winner.excludeRules + other.excludeRules)
            .toSortedSet(compareBy(ExcludeRule::toString)),
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
): Map<String, ResolvedDependency> {
    val coveredByShortId = coveredDependencies.groupBy { it.dependency.shortId }
    return mapNotNull { (shortId, dependency) ->
        val coveringDependencies = coveredByShortId[shortId]
            .orEmpty()
            .filter { covered -> covered.canCover(dependency) }
        val exactCoveredDependency = coveringDependencies.firstOrNull { covered ->
            covered.dependency.hasSameEffectiveIdentityAs(dependency)
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
    return this.dependency.hasSameBucketOwnerAs(dependency) &&
        (!dependency.direct || this.dependency.direct)
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
                nonDefaultDependency.hasSameBucketOwnerAs(dependency)
            }
        !hasSameNonDefaultOwner ||
            hierarchyDefaultDeps[dependency.shortId]?.hasSameBucketOwnerAs(dependency) == true
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
        if (closureList.all { closure -> closure[id]?.hasSameBucketOwnerAs(candidate) == true }) {
            id to candidate
        } else {
            null
        }
    }.toMap()
}

private fun CoveredDependency.toOverrideTarget(): OverrideTarget {
    return mavenOverrideTarget(dependency.shortId, bucketName)
}
