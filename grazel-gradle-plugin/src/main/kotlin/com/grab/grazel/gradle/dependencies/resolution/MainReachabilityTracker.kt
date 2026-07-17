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

package com.grab.grazel.gradle.dependencies.resolution

import com.grab.grazel.gradle.dependencies.AggregatedDependencyRootKind
import com.grab.grazel.gradle.dependencies.AggregatedDependencyRootMetadata
import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.DeclaredProjectDependency
import com.grab.grazel.gradle.dependencies.DeclaredVariantDependencyMetadata
import com.grab.grazel.gradle.dependencies.ProjectDependencyBucket
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.selectedVariantHierarchyNames
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.JvmBuild

/**
 * Tracks which sub-projects and variant buckets are reachable from a main (app) hierarchy root,
 * and the per-root `project(...)` dependency edge exclude scopes discovered while walking those
 * roots. Relocated verbatim from `AggregatedDependencyResolver.ResolutionSession` (behaviour
 * unchanged) as the first step of decomposing that god-class.
 *
 * State is folded in [recordMainRoot] as each MAIN_HIERARCHY/MAIN_LEAF root is visited, in
 * `workspaceDependencyRoots` order, so that later roots (including test roots, which never mutate
 * this state) observe already-folded reachability facts.
 */
internal class MainReachabilityTracker(
    private val declaredDependencyMetadata: DeclaredDependencyMetadata,
    private val migratableProjectPaths: List<String>,
) {
    private val projectMetadataByPath = declaredDependencyMetadata.projects
    private val declaredProjectDependencyEdgesCache =
        mutableMapOf<Triple<String, Set<String>, Boolean>, List<DeclaredProjectDependency>>()
    private val mainBuildTypeNamesByProject = migratableProjectPaths.associateWith { projectPath ->
        variantsFor(projectPath)
            .asSequence()
            .filter { variant -> variant.variantType == AndroidBuild }
            .filter(DeclaredVariantDependencyMetadata::androidLeafVariant)
            .mapNotNull(DeclaredVariantDependencyMetadata::buildType)
            .toSet()
    }

    val reachableMainProjectPaths = sortedSetOf<String>()
    val reachableMainBucketNamesByProject = sortedMapOf<String, MutableSet<String>>()
    private val mainProjectEdgeScopes = mutableListOf<MainProjectEdgeScope>()

    private fun variantsFor(projectPath: String): List<DeclaredVariantDependencyMetadata> =
        projectMetadataByPath[projectPath]?.variants.orEmpty()

    private fun declaredProjectDependencyEdges(
        projectPath: String,
        variantNames: Set<String>,
        selectedOnly: Boolean
    ): List<DeclaredProjectDependency> {
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
            .filter { edge -> edge.targetProjectPath in migratableProjectPaths }
            .toList()
            .also { edges -> declaredProjectDependencyEdgesCache[cacheKey] = edges }
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
            .associate { variant -> variant.name to (setOf(variant.name) + variant.extendsFrom) } +
            mapOf(
                "apiElements" to setOf(DEFAULT_VARIANT),
                "runtimeElements" to setOf(DEFAULT_VARIANT)
            )
        return selectedVariantHierarchyNames(
            displayName = selectedVariantDisplayName,
            variantHierarchyNamesByName = variantHierarchyNamesByName
        ).orDefaultVariantIn(variantHierarchyNamesByName.keys)
    }

    /**
     * Falls back to [DEFAULT_VARIANT] when this set of bucket names is empty, but only if
     * `default` is itself a known name — so an empty result never invents a bucket the project
     * doesn't actually have.
     */
    private fun Set<String>.orDefaultVariantIn(knownNames: Set<String>): Set<String> =
        ifEmpty { setOf(DEFAULT_VARIANT).filter { name -> name in knownNames }.toSet() }

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
            .orDefaultVariantIn(knownBucketNames)
    }

    fun isReachableMainBucket(bucket: ProjectDependencyBucket): Boolean {
        return bucket.bucketName in reachableMainBucketNamesByProject[bucket.projectPath].orEmpty()
    }

    /**
     * A declared dependency is only dropped from [bucket] if *every* main-hierarchy root scope
     * that can reach `bucket.projectPath` excludes it — a single reachable root without the
     * exclusion is enough to keep it (intersection, not union, of exclusions). This mirrors
     * Gradle semantics where a `project(...)` edge's `exclude` only applies along that edge, so
     * a dependency excluded via one app variant's edge but not another's must still be resolved.
     */
    fun filterExcludedByEveryReachableRoot(
        dependenciesByProjectBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
    ): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
        if (mainProjectEdgeScopes.isEmpty()) return dependenciesByProjectBucket
        val reachableScopesByProjectPath = mainProjectEdgeScopes
            .flatMap { scope ->
                scope.reachableProjectPaths.map { projectPath -> projectPath to scope }
            }
            .groupBy(
                keySelector = { (projectPath, _) -> projectPath },
                valueTransform = { (_, scope) -> scope }
            )
        return dependenciesByProjectBucket.mapValues { (bucket, dependencies) ->
            val reachableScopes = reachableScopesByProjectPath[bucket.projectPath].orEmpty()
            dependencies.filterKeys { shortId ->
                reachableScopes.isEmpty() ||
                    reachableScopes.any { scope ->
                        shortId !in scope.excludedShortIdsByTargetProject[bucket.projectPath].orEmpty()
                    }
            }
        }
    }

    fun shouldResolveMainHierarchyRoot(metadata: AggregatedDependencyRootMetadata): Boolean {
        if (metadata.kind != AggregatedDependencyRootKind.MAIN_HIERARCHY || metadata.variantType != AndroidBuild) {
            return true
        }
        val bucket = metadata.bucketName ?: return true
        if (bucket == DEFAULT_VARIANT) return true
        return bucket in mainBuildTypeNamesByProject[metadata.projectPath].orEmpty()
    }

    /**
     * DFS over declared `project(...)` dependency edges reachable from [projectPath] for the
     * given [variantNames], recording (a) every reachable project path, (b) which of its
     * variant/bucket names are reached, and (c) exclude-rule short IDs attached per target
     * project. This is the load-bearing pass that later filters declared-dependency buckets to
     * only those actually wired into an app's dependency graph, and that seeds the
     * per-root exclusion intersection in [filterExcludedByEveryReachableRoot].
     *
     * [scopeReachableProjectPaths] doubles as the visited set, so a project already visited in
     * this DFS is not revisited (guards against cycles / diamond dependencies) — but its edges'
     * exclude rules are still recorded before the cycle check via [visit]'s ordering: excludes
     * are collected for every edge out of a project the first time it's visited, so this must
     * remain a single-pass, not-revisit DFS to keep results deterministic.
     */
    fun computeScope(
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

    /**
     * Folds a freshly [computeScope]-d [MainProjectEdgeScope] for a MAIN_HIERARCHY/MAIN_LEAF root
     * into this tracker's accumulated reachability state: the scope is recorded for later
     * exclusion-intersection lookups ([filterExcludedByEveryReachableRoot]), and its reachable
     * project paths / bucket names are folded into [reachableMainProjectPaths] /
     * [reachableMainBucketNamesByProject] so subsequent roots (including test roots) observe them.
     */
    fun recordMainRoot(metadata: AggregatedDependencyRootMetadata, scope: MainProjectEdgeScope) {
        mainProjectEdgeScopes.add(scope)
        reachableMainProjectPaths.addAll(scope.reachableProjectPaths)
        scope.reachableBucketNamesByProject.forEach { (projectPath, bucketNames) ->
            addReachableMainBuckets(projectPath, bucketNames)
        }
    }

    /**
     * Folds a [com.grab.grazel.gradle.dependencies.resolution] `RootVisitOutcome` reachability
     * delta into this tracker. No-op stub for this task; wired in Task 2 once
     * `resolveRootToDependencyMap` returns a `RootVisitOutcome` instead of mutating out-params.
     */
    fun recordReachable(projectPaths: Set<String>, bucketNamesByProject: Map<String, Set<String>>) {
        // Intentionally a no-op until Task 2 (RootVisitOutcome conversion) wires this in; the
        // session still folds reachability directly via the mutable maps exposed above.
    }
}
