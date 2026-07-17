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

import com.grab.grazel.gradle.dependencies.bucket.BucketOwnershipPlanner
import com.grab.grazel.gradle.dependencies.bucket.OwnershipPlannerInput
import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.intersectWith
import com.grab.grazel.gradle.dependencies.model.versionInfo
import com.grab.grazel.gradle.dependencies.resolution.BucketTarget
import com.grab.grazel.gradle.dependencies.resolution.DependencyBucketAccumulator
import com.grab.grazel.gradle.dependencies.resolution.MainProjectEdgeScope
import com.grab.grazel.gradle.dependencies.resolution.MainReachabilityTracker
import com.grab.grazel.gradle.dependencies.resolution.RootVisitOutcome
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.Test
import com.grab.grazel.util.ProgressReporter
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
    private val reporter: ProgressReporter,
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

    /**
     * One-shot, stateful engine for a single [resolve] call: walks every workspace dependency root
     * in [workspaceDependencyRoots], resolves each root's classpath, and buckets the resulting
     * closures by [AggregatedDependencyRootKind] into the mutable maps below. State is accumulated
     * across roots (not per-root) because later roots depend on reachability/exclusion facts
     * discovered while processing earlier main-hierarchy roots — hence this is a fresh instance per
     * [resolve] invocation rather than a reusable service.
     *
     * Key accumulated state and how it is used downstream:
     * - [mainReachabilityTracker]: which sub-projects and variant buckets are actually reachable
     *   from a main (app) hierarchy root, used to prune declared-dependency buckets that no root
     *   ever reaches, plus per-root `project(...)` dependency edge exclude scopes intersected in
     *   [MainReachabilityTracker.filterExcludedByEveryReachableRoot].
     * - The `*Closures` maps hold merged [ResolvedDependency] maps per [ProjectDependencyBucket],
     *   unioned across all roots contributing to that bucket, and are handed to
     *   [BucketOwnershipPlanner] once all roots have been visited.
     */
    private inner class ResolutionSession {
        private val projectMetadataByPath = declaredDependencyMetadata.projects
        private val migratableProjectPaths = projectMetadataByPath.keys.sorted()
        private val mainReachabilityTracker =
            MainReachabilityTracker(declaredDependencyMetadata, migratableProjectPaths)

        private val bucketAccumulator = DependencyBucketAccumulator()
        private var sawBinaryRoot = false

        fun resolve(): List<ResolveDependenciesResult> {
            if (migratableProjectPaths.isEmpty()) return emptyList()

            collectRootClosures()
            if (!sawBinaryRoot) {
                logger.warn("Grazel: No migratable binary modules found for aggregated dependency resolution")
                return emptyList()
            }

            val declaredTestDependenciesByBucket = addDeclaredMetadataClosures()
            if (!bucketAccumulator.hasResolvedClosures()) {
                logger.warn("Grazel: No leaf variant runtime classpaths resolved")
            }

            return BucketOwnershipPlanner(
                declaredDependencyMetadata = declaredDependencyMetadata,
                precomputedKspDependencies = precomputedKspDependencies
            ).plan(
                OwnershipPlannerInput(
                    leafClosures = bucketAccumulator.leaf(),
                    leafUnitTestClosures = bucketAccumulator.leafUnitTest(),
                    leafAndroidTestClosures = bucketAccumulator.leafAndroidTest(),
                    hierarchyBucketClosures = bucketAccumulator.hierarchy(),
                    testHierarchyBucketClosures = bucketAccumulator.testHierarchy(),
                    reachableMainBucketNamesByProject = mainReachabilityTracker.reachableMainBucketNamesByProject
                        .mapValues { (_, bucketNames) -> bucketNames.toSortedSet() }
                        .toSortedMap(),
                    lintDeps = bucketAccumulator.lintDeps.toSortedMap(),
                    declaredTestDependenciesByBucket = snapshotDependencyBuckets(declaredTestDependenciesByBucket)
                )
            )
        }

        private fun snapshotDependencyBuckets(
            dependenciesByProjectBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
        ): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
            return dependenciesByProjectBucket.toSortedMap(
                compareBy<ProjectDependencyBucket> { bucket -> bucket.projectPath }
                    .thenBy { bucket -> bucket.bucketName }
            ).mapValues { (_, dependencies) -> dependencies.toSortedMap() }
        }

        private fun variantsFor(projectPath: String): List<DeclaredVariantDependencyMetadata> =
            projectMetadataByPath[projectPath]?.variants.orEmpty()

        private fun variantHierarchyNames(metadata: AggregatedDependencyRootMetadata): Set<String> {
            return metadata.variantNames.ifEmpty {
                listOfNotNull(metadata.leafName, metadata.bucketName).toSet()
            }
        }

        private fun targetBucketNames(metadata: AggregatedDependencyRootMetadata): Set<String> {
            return metadata.targetBuckets.ifEmpty {
                listOfNotNull(metadata.bucketName).toSet()
            }
        }

        private fun excludeRulesFor(
            metadata: AggregatedDependencyRootMetadata,
            variantType: VariantType
        ): Map<String, ProjectExcludeRules> {
            return declaredDependencyMetadata.collectExcludeRulesByProjectPath(
                variantTypes = setOf(variantType),
                variantNames = variantHierarchyNames(metadata)
            )
        }

        private fun shouldAddDeclaredHierarchyDependency(bucket: ProjectDependencyBucket): Boolean {
            val projectType = projectMetadataByPath[bucket.projectPath]?.projectType
                ?: DeclaredProjectType.OTHER
            return projectType == DeclaredProjectType.OTHER || bucket.bucketName != DEFAULT_VARIANT
        }

        /**
         * Visits every eligible workspace dependency root and dispatches per [AggregatedDependencyRootKind]
         * to resolve, exclude-filter and bucket its classpath. Ordering matters: for a given root,
         * reachability state ([MainReachabilityTracker.reachableMainProjectPaths] /
         * [MainReachabilityTracker.reachableMainBucketNamesByProject]) is populated from
         * [MainReachabilityTracker.computeScope] *before* the closure is resolved, and MAIN_HIERARCHY/
         * MAIN_LEAF roots must be processed (in [workspaceDependencyRoots] order) before any
         * TEST_HIERARCHY/UNIT_TEST/ANDROID_TEST root that depends on the same project's main
         * reachability facts. Every non-LINT kind folds the [RootVisitOutcome] returned by
         * `resolveRootToDependencyMap` back into the tracker via
         * [MainReachabilityTracker.recordReachable], so any `project(...)` edge discovered while
         * walking a TEST_HIERARCHY/UNIT_TEST/ANDROID_TEST root's classpath also feeds later roots'
         * reachability facts, mirroring the in-place mutation this replaced.
         */
        private fun collectRootClosures() {
            val rootsToResolve = workspaceDependencyRoots.filter { root ->
                mainReachabilityTracker.shouldResolveMainHierarchyRoot(root.metadata)
            }
            rootsToResolve.forEachIndexed rootLoop@{ index, aggregatedRoot ->
                val metadata = aggregatedRoot.metadata
                reporter.report("resolving (${index + 1}/${rootsToResolve.size}): ${metadata.projectPath}")
                var mainProjectEdgeScope: MainProjectEdgeScope? = null
                when (metadata.kind) {
                    AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    AggregatedDependencyRootKind.MAIN_LEAF -> {
                        val scope = mainReachabilityTracker.computeScope(
                            projectPath = metadata.projectPath,
                            variantNames = variantHierarchyNames(metadata),
                            selectedOnly = true
                        )
                        mainReachabilityTracker.recordMainRoot(metadata, scope)
                        mainProjectEdgeScope = scope
                    }
                    else -> Unit
                }
                when (metadata.kind) {
                    AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    AggregatedDependencyRootKind.MAIN_LEAF -> mainReachabilityTracker.addReachableMainBuckets(
                        projectPath = metadata.projectPath,
                        bucketNames = variantHierarchyNames(metadata)
                    )
                    else -> Unit
                }
                val closure = when (metadata.kind) {
                    AggregatedDependencyRootKind.LINT ->
                        resolveRootToDependencyMap(aggregatedRoot).dependencies
                    AggregatedDependencyRootKind.MAIN_HIERARCHY,
                    AggregatedDependencyRootKind.MAIN_LEAF -> {
                        val outcome = resolveRootToDependencyMap(
                            aggregatedRoot = aggregatedRoot,
                            excludeRulesByProjectPath = excludeRulesFor(metadata, AndroidBuild),
                            projectEdgeExcludedShortIdsByTargetProject = mainProjectEdgeScope
                                ?.excludedShortIdsByTargetProject.orEmpty(),
                            reachableBucketNamesForProject = mainReachabilityTracker::selectedMainVariantHierarchyNames
                        )
                        mainReachabilityTracker.recordReachable(
                            outcome.reachableProjectPaths,
                            outcome.reachableBucketNamesByProject
                        )
                        outcome.dependencies
                    }
                    AggregatedDependencyRootKind.TEST_HIERARCHY -> {
                        val outcome = resolveRootToDependencyMap(
                            aggregatedRoot = aggregatedRoot,
                            excludeRulesByProjectPath = excludeRulesFor(
                                metadata = metadata,
                                variantType = metadata.variantType ?: when (metadata.bucketName) {
                                    ANDROID_TEST_VARIANT -> AndroidTest
                                    else -> Test
                                }
                            ),
                            reachableBucketNamesForProject = mainReachabilityTracker::selectedMainVariantHierarchyNames
                        )
                        mainReachabilityTracker.recordReachable(
                            outcome.reachableProjectPaths,
                            outcome.reachableBucketNamesByProject
                        )
                        outcome.dependencies
                    }
                    AggregatedDependencyRootKind.UNIT_TEST -> {
                        val leafHierarchyNames = variantHierarchyNames(metadata)
                        val outcome = resolveRootToDependencyMap(
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
                            reachableBucketNamesForProject = mainReachabilityTracker::selectedMainVariantHierarchyNames
                        )
                        mainReachabilityTracker.recordReachable(
                            outcome.reachableProjectPaths,
                            outcome.reachableBucketNamesByProject
                        )
                        outcome.dependencies
                    }
                    AggregatedDependencyRootKind.ANDROID_TEST -> {
                        val leafHierarchyNames = variantHierarchyNames(metadata)
                        val outcome = resolveRootToDependencyMap(
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
                            reachableBucketNamesForProject = mainReachabilityTracker::selectedMainVariantHierarchyNames
                        )
                        mainReachabilityTracker.recordReachable(
                            outcome.reachableProjectPaths,
                            outcome.reachableBucketNamesByProject
                        )
                        outcome.dependencies
                    }
                }

                when (metadata.kind) {
                    AggregatedDependencyRootKind.MAIN_HIERARCHY -> {
                        sawBinaryRoot = true
                        targetBucketNames(metadata).forEach { bucketName ->
                            when (bucketName) {
                                TEST_VARIANT, ANDROID_TEST_VARIANT ->
                                    bucketAccumulator.fold(
                                        target = BucketTarget.TEST_HIERARCHY,
                                        projectPath = metadata.projectPath,
                                        bucketName = bucketName,
                                        closure = closure,
                                        keepEmpty = false
                                    )
                                else -> bucketAccumulator.fold(
                                    target = BucketTarget.HIERARCHY,
                                    projectPath = metadata.projectPath,
                                    bucketName = bucketName,
                                    closure = closure,
                                    keepEmpty = false
                                )
                            }
                        }
                    }
                    AggregatedDependencyRootKind.MAIN_LEAF -> {
                        sawBinaryRoot = true
                        val leafName = metadata.leafName ?: metadata.bucketName ?: return@rootLoop
                        val targetBuckets = targetBucketNames(metadata)
                        targetBuckets.forEach { bucketName ->
                            when (bucketName) {
                                DEFAULT_VARIANT -> bucketAccumulator.fold(
                                    target = BucketTarget.HIERARCHY,
                                    projectPath = metadata.projectPath,
                                    bucketName = DEFAULT_VARIANT,
                                    closure = closure,
                                    keepEmpty = false
                                )
                                TEST_VARIANT ->
                                    bucketAccumulator.fold(
                                        target = BucketTarget.LEAF_UNIT_TEST,
                                        projectPath = metadata.projectPath,
                                        bucketName = leafName,
                                        closure = closure
                                    )
                                ANDROID_TEST_VARIANT ->
                                    bucketAccumulator.fold(
                                        target = BucketTarget.LEAF_ANDROID_TEST,
                                        projectPath = metadata.projectPath,
                                        bucketName = leafName,
                                        closure = closure
                                    )
                                else -> {
                                    bucketAccumulator.fold(
                                        target = BucketTarget.LEAF,
                                        projectPath = metadata.projectPath,
                                        bucketName = bucketName,
                                        closure = closure
                                    )
                                }
                            }
                        }
                    }
                    AggregatedDependencyRootKind.TEST_HIERARCHY -> {
                        targetBucketNames(metadata).forEach { bucketName ->
                            bucketAccumulator.fold(
                                target = BucketTarget.TEST_HIERARCHY,
                                projectPath = metadata.projectPath,
                                bucketName = bucketName,
                                closure = closure,
                                keepEmpty = false
                            )
                        }
                    }
                    AggregatedDependencyRootKind.UNIT_TEST -> {
                        val leafName = metadata.leafName ?: metadata.bucketName ?: return@rootLoop
                        bucketAccumulator.fold(
                            target = BucketTarget.LEAF_UNIT_TEST,
                            projectPath = metadata.projectPath,
                            bucketName = leafName,
                            closure = closure
                        )
                    }
                    AggregatedDependencyRootKind.ANDROID_TEST -> {
                        val leafName = metadata.leafName ?: metadata.bucketName ?: return@rootLoop
                        bucketAccumulator.fold(
                            target = BucketTarget.LEAF_ANDROID_TEST,
                            projectPath = metadata.projectPath,
                            bucketName = leafName,
                            closure = closure
                        )
                    }
                    AggregatedDependencyRootKind.LINT -> {
                        bucketAccumulator.foldLint(closure)
                    }
                }
            }
        }

        /**
         * Merges purely-declared (never resolved via a classpath, e.g. `compileOnly` or projects
         * with no resolvable root) dependency buckets into the same hierarchy closures the resolved
         * roots populated, in a specific order that each filter depends on:
         * 1. compileOnly buckets are added unconditionally (subject only to
         *    [shouldAddDeclaredHierarchyDependency]) since they never appear on a runtime classpath
         *    and so cannot be discovered by root traversal at all.
         * 2. declared "main" (implementation/api) buckets are added only if the bucket is both
         *    non-OTHER-eligible ([shouldAddDeclaredHierarchyDependency]) *and* actually reached by a
         *    main-hierarchy root ([MainReachabilityTracker.isReachableMainBucket]) — this must run
         *    after [collectRootClosures] has populated reachability — and are then passed through
         *    [MainReachabilityTracker.filterExcludedByEveryReachableRoot] so a dependency excluded
         *    on every reachable edge is still dropped even though it was never resolved.
         * 3. declared test buckets are added last and unconditionally into the test hierarchy
         *    closures, since test source sets have no equivalent reachability gating.
         */
        private fun addDeclaredMetadataClosures(): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
            val compileOnlyDependenciesByBucket = declaredDependencyMetadata
                .collectCompileOnlyDependenciesByProjectBucket(projectMetadataByPath.keys)
            val declaredMainDependenciesByBucket = declaredDependencyMetadata
                .collectDeclaredMainDependenciesByProjectBucket(projectMetadataByPath.keys)

            compileOnlyDependenciesByBucket
                .filter { (bucket, _) -> shouldAddDeclaredHierarchyDependency(bucket) }
                .forEach { (bucket, dependencies) ->
                    when (bucket.bucketName) {
                        TEST_VARIANT, ANDROID_TEST_VARIANT ->
                            bucketAccumulator.fold(
                                target = BucketTarget.TEST_HIERARCHY,
                                projectPath = bucket.projectPath,
                                bucketName = bucket.bucketName,
                                closure = dependencies,
                                keepEmpty = false
                            )
                        else -> bucketAccumulator.fold(
                            target = BucketTarget.HIERARCHY,
                            projectPath = bucket.projectPath,
                            bucketName = bucket.bucketName,
                            closure = dependencies,
                            keepEmpty = false
                        )
                    }
                }

            declaredMainDependenciesByBucket
                .filter { (bucket, _) -> shouldAddDeclaredHierarchyDependency(bucket) }
                .filter { (bucket, _) -> mainReachabilityTracker.isReachableMainBucket(bucket) }
                .let(mainReachabilityTracker::filterExcludedByEveryReachableRoot)
                .filterValues(Map<String, ResolvedDependency>::isNotEmpty)
                .forEach { (bucket, dependencies) ->
                    bucketAccumulator.fold(
                        target = BucketTarget.HIERARCHY,
                        projectPath = bucket.projectPath,
                        bucketName = bucket.bucketName,
                        closure = dependencies,
                        keepEmpty = false
                    )
                }

            return declaredDependencyMetadata
                .collectDeclaredTestDependenciesByProjectBucket(projectMetadataByPath.keys)
                .also { declaredTestDependenciesByBucket ->
                    declaredTestDependenciesByBucket.forEach { (bucket, dependencies) ->
                        bucketAccumulator.fold(
                            target = BucketTarget.TEST_HIERARCHY,
                            projectPath = bucket.projectPath,
                            bucketName = bucket.bucketName,
                            closure = dependencies,
                            keepEmpty = false
                        )
                    }
                }
        }

    }

    /**
     * Widens [leafHierarchyNames] (a single leaf variant plus its `extendsFrom` chain) with the
     * names of any [testType] test variant that extends — directly or transitively via its own
     * `extendsFrom` — one of those leaf names. Both the test variant's own name and everything it
     * extends from are unioned in, not just the matched test variant, because exclude-rule lookups
     * ([ProjectExcludeRules.rulesFor]) need the full hierarchy to find rules attached anywhere along
     * the chain. Used only to scope exclude-rule collection for UNIT_TEST/ANDROID_TEST roots — the
     * leaf variant itself may have no test variant pointing at it, in which case this is a no-op.
     */
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
     * Walks one resolved root's component graph via [ResolvedComponentsVisitor] into a
     * `shortId -> ResolvedDependency` map, returned as [RootVisitOutcome] alongside the
     * `project(...)` edge reachability discovered along the way (for the caller to fold via
     * [MainReachabilityTracker.recordReachable]). Two aspects are load-bearing for byte-identical,
     * reproducible output across builds:
     * - BOM/platform (pom-only) components are dropped ([Component.isBomComponent]) since
     *   `rules_jvm_external` rejects pom-packaged artifacts, and (when [AggregatedDependencyRootMetadata.traverseProjectNodes]
     *   is set) any node not directly reached from a project edge is skipped — project-node
     *   traversal roots only care about what a `project(...)` edge itself contributes.
     * - Visit results are explicitly sorted (component string, repository, direct project path/
     *   variant, directFromProject, requiresJetifier) *before* being folded into [depMap], so that
     *   iteration order — and therefore which duplicate short-ID wins ties in
     *   [mergeDependencyMetadataByMaxVersion] — is deterministic regardless of the underlying
     *   Gradle resolution graph's internal (unordered) traversal order.
     *
     * Exclude rules per dependency are computed differently depending on whether this root
     * traverses project nodes: project-traversing roots resolve rules per-owner via
     * [excludeRulesForDependency] (owner + root precedence), non-traversing roots use the root's
     * own flat `rootExcludeRulesByShortId` map directly. Duplicate short IDs encountered while
     * walking (e.g. reached via multiple paths) are folded together with
     * [mergeDependencyMetadataByMaxVersion] rather than overwritten.
     */
    private fun resolveRootToDependencyMap(
        aggregatedRoot: AggregatedDependencyRoot,
        excludeRulesByProjectPath: Map<String, ProjectExcludeRules> = emptyMap(),
        projectEdgeExcludedShortIdsByTargetProject: Map<String, Set<String>> = emptyMap(),
        reachableBucketNamesForProject: ((String, String?) -> Set<String>)? = null
    ): RootVisitOutcome {
        val metadata = aggregatedRoot.metadata
        return try {
            val depMap = mutableMapOf<String, ResolvedDependency>()
            val reachableProjectPaths = mutableSetOf<String>()
            val reachableBucketNamesByProject = mutableMapOf<String, MutableSet<String>>()
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
                        reachableProjectPaths.add(projectPath)
                        val reachableBucketNames = reachableBucketNamesForProject
                            ?.invoke(projectPath, visitResult.directProjectVariantDisplayName)
                            .orEmpty()
                        reachableBucketNamesByProject
                            .getOrPut(projectPath) { sortedSetOf() }
                            .addAll(reachableBucketNames)
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
                        excludeRulesForDependency(
                            excludeRulesByProjectPath = excludeRulesByProjectPath,
                            rootProjectPath = metadata.projectPath,
                            rootExcludeRulesByShortId = metadata.rootExcludeRulesByShortId,
                            ownerProjectPath = visitResult.directProjectPath,
                            ownerProjectVariantDisplayName = visitResult.directProjectVariantDisplayName,
                            ownerProjectVariantAttrName = visitResult.directProjectVariantName,
                            shortId = shortId,
                        )
                    } else {
                        metadata.rootExcludeRulesByShortId.getOrDefault(shortId, emptySet())
                    }
                    val dependency = ResolvedDependency(
                        id = "${moduleVersion.group}:${moduleVersion.name}:${moduleVersion.version}",
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
            RootVisitOutcome(
                dependencies = depMap,
                reachableProjectPaths = reachableProjectPaths,
                reachableBucketNamesByProject = reachableBucketNamesByProject
            )
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to resolve aggregated root ${metadata.configurationName} " +
                    "for ${metadata.projectPath}",
                e
            )
        }
    }

}

/**
 * Picks a winner between two [ResolvedDependency] records for the same short ID and merges the
 * loser's metadata into it. Winner selection order:
 * 1. A declared-only record (never seen on a resolved classpath, see [ResolvedDependency.isDeclaredMetadata])
 *    always loses to a resolved one, regardless of version — resolution reflects Gradle's actual
 *    conflict-resolved version and must take precedence over a manifest declaration.
 * 2. Otherwise (both or neither declared) the higher [versionInfo] wins; exact ties keep [candidate]
 *    to preserve encounter order.
 *
 * Post-selection merge rules differ by field because they encode different semantics:
 * - `dependencies` (transitive edge set) is unioned only when both sides resolved to the *same*
 *   version — merging transitive edges across differing versions would fabricate an edge set that
 *   never existed for either resolved graph.
 * - `excludeRules`: when exactly one side is declared-only metadata, rules are unioned (a manifest
 *   declaration's excludes are additive since it was never actually resolved with them applied);
 *   when both sides are real resolved records, rules are intersected, matching the "excluded only if
 *   excluded everywhere" semantics used elsewhere in this file.
 * - `requiresJetifier` is OR'd across both sides (true if either side needs jetifying), rather than
 *   preferring the winner's value.
 * - `jetifierSource`, `overrideTarget`, `processorClass` all prefer the winner's value but fall back
 *   to the loser's so metadata isn't silently dropped when only one side carries it.
 */
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
