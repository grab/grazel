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
import com.grab.grazel.gradle.dependencies.resolution.DeclaredMetadataMerger
import com.grab.grazel.gradle.dependencies.resolution.DependencyBucketAccumulator
import com.grab.grazel.gradle.dependencies.resolution.MainReachabilityTracker
import com.grab.grazel.gradle.dependencies.resolution.RootContributionComputer
import com.grab.grazel.gradle.dependencies.resolution.RootVisitOutcome
import com.grab.grazel.gradle.dependencies.resolution.snapshotDependencyBuckets
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
        private val rootContributionComputer = RootContributionComputer(
            tracker = mainReachabilityTracker,
            declaredDependencyMetadata = declaredDependencyMetadata,
            resolveRoot = this@AggregatedDependencyResolver::resolveRootToDependencyMap
        )

        /**
         * Visits every eligible workspace dependency root and dispatches per [AggregatedDependencyRootKind]
         * (via [RootContributionComputer]) to resolve, exclude-filter and bucket its classpath.
         * Ordering matters: for a given root, reachability state
         * ([MainReachabilityTracker.reachableMainProjectPaths] /
         * [MainReachabilityTracker.reachableMainBucketNamesByProject]) is populated from
         * [MainReachabilityTracker.computeScope] *before* the closure is resolved (handled inside
         * [RootContributionComputer.compute] itself for MAIN_HIERARCHY/MAIN_LEAF roots), and
         * MAIN_HIERARCHY/MAIN_LEAF roots must be processed (in [workspaceDependencyRoots] order)
         * before any TEST_HIERARCHY/UNIT_TEST/ANDROID_TEST root that depends on the same project's
         * main reachability facts. Every non-LINT kind folds the [RootVisitOutcome] returned by
         * `resolveRootToDependencyMap` back into the tracker via
         * [MainReachabilityTracker.recordReachable] (LINT never did, so [RootContribution.lintClosure]
         * being non-null gates this fold out), so any `project(...)` edge discovered while walking a
         * TEST_HIERARCHY/UNIT_TEST/ANDROID_TEST root's classpath also feeds later roots' reachability
         * facts, mirroring the in-place mutation this replaced.
         */
        fun resolve(): List<ResolveDependenciesResult> {
            if (migratableProjectPaths.isEmpty()) return emptyList()

            val rootsToResolve = workspaceDependencyRoots.filter { root ->
                mainReachabilityTracker.shouldResolveMainHierarchyRoot(root.metadata)
            }
            rootsToResolve.forEachIndexed { index, aggregatedRoot ->
                val metadata = aggregatedRoot.metadata
                reporter.report("resolving (${index + 1}/${rootsToResolve.size}): ${metadata.projectPath}")

                val contribution = rootContributionComputer.compute(aggregatedRoot)
                if (contribution.lintClosure == null) {
                    // MAIN_HIERARCHY/MAIN_LEAF roots already seed their reachability scope earlier,
                    // inside RootContributionComputer.compute (the classpath walk must observe the
                    // seeded scope) — so this fold only records what was *discovered* while walking,
                    // and intentionally does not also seed MAIN scope here (that would double-seed).
                    mainReachabilityTracker.recordReachable(
                        contribution.outcome.reachableProjectPaths,
                        contribution.outcome.reachableBucketNamesByProject
                    )
                }
                contribution.routing.forEach { routing ->
                    bucketAccumulator.fold(
                        target = routing.target,
                        projectPath = metadata.projectPath,
                        bucketName = routing.bucketName,
                        closure = contribution.outcome.dependencies,
                        keepEmpty = routing.target != BucketTarget.HIERARCHY &&
                            routing.target != BucketTarget.TEST_HIERARCHY
                    )
                }
                contribution.lintClosure?.let(bucketAccumulator::foldLint)
                if (contribution.seedsBinaryRoot) sawBinaryRoot = true
            }

            if (!sawBinaryRoot) {
                logger.warn("Grazel: No migratable binary modules found for aggregated dependency resolution")
                return emptyList()
            }

            val declaredTestDependenciesByBucket = DeclaredMetadataMerger(
                declaredDependencyMetadata = declaredDependencyMetadata,
                projectMetadataByPath = projectMetadataByPath
            ).merge(bucketAccumulator, mainReachabilityTracker)
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
