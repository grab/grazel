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

import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.DeclaredProjectType
import com.grab.grazel.gradle.dependencies.ProjectDeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.ProjectDependencyBucket
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.ANDROID_TEST_VARIANT
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.TEST_VARIANT

/**
 * Merges purely-declared (never resolved via a classpath, e.g. `compileOnly` or projects with no
 * resolvable root) dependency buckets into the same hierarchy closures the resolved roots
 * populated, in a specific order that each filter depends on. Relocated verbatim from
 * `AggregatedDependencyResolver.ResolutionSession.addDeclaredMetadataClosures` (behaviour
 * unchanged) as part of decomposing that god-class:
 * 1. compileOnly buckets are added unconditionally (subject only to
 *    [shouldAddDeclaredHierarchyDependency]) since they never appear on a runtime classpath and so
 *    cannot be discovered by root traversal at all.
 * 2. declared "main" (implementation/api) buckets are added only if the bucket is both
 *    non-OTHER-eligible ([shouldAddDeclaredHierarchyDependency]) *and* actually reached by a
 *    main-hierarchy root ([MainReachabilityTracker.isReachableMainBucket]) — this must run after
 *    root closures have been collected so reachability is populated — and are then passed through
 *    [MainReachabilityTracker.filterExcludedByEveryReachableRoot] so a dependency excluded on
 *    every reachable edge is still dropped even though it was never resolved.
 * 3. declared test buckets are added last and unconditionally into the test hierarchy closures,
 *    since test source sets have no equivalent reachability gating.
 */
internal class DeclaredMetadataMerger(
    private val declaredDependencyMetadata: DeclaredDependencyMetadata,
    private val projectMetadataByPath: Map<String, ProjectDeclaredDependencyMetadata>,
) {

    fun merge(
        accumulator: DependencyBucketAccumulator,
        tracker: MainReachabilityTracker,
    ): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
        val compileOnlyDependenciesByBucket = declaredDependencyMetadata
            .collectCompileOnlyDependenciesByProjectBucket(projectMetadataByPath.keys)
        val declaredMainDependenciesByBucket = declaredDependencyMetadata
            .collectDeclaredMainDependenciesByProjectBucket(projectMetadataByPath.keys)

        compileOnlyDependenciesByBucket
            .filter { (bucket, _) -> shouldAddDeclaredHierarchyDependency(bucket) }
            .forEach { (bucket, dependencies) ->
                val target = when (bucket.bucketName) {
                    TEST_VARIANT, ANDROID_TEST_VARIANT -> BucketTarget.TEST_HIERARCHY
                    else -> BucketTarget.HIERARCHY
                }
                accumulator.fold(
                    target = target,
                    projectPath = bucket.projectPath,
                    bucketName = bucket.bucketName,
                    closure = dependencies,
                    keepEmpty = false
                )
            }

        declaredMainDependenciesByBucket
            .filter { (bucket, _) -> shouldAddDeclaredHierarchyDependency(bucket) }
            .filter { (bucket, _) -> tracker.isReachableMainBucket(bucket) }
            .let(tracker::filterExcludedByEveryReachableRoot)
            .filterValues(Map<String, ResolvedDependency>::isNotEmpty)
            .forEach { (bucket, dependencies) ->
                accumulator.fold(
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
                    accumulator.fold(
                        target = BucketTarget.TEST_HIERARCHY,
                        projectPath = bucket.projectPath,
                        bucketName = bucket.bucketName,
                        closure = dependencies,
                        keepEmpty = false
                    )
                }
            }
    }

    private fun shouldAddDeclaredHierarchyDependency(bucket: ProjectDependencyBucket): Boolean {
        val projectType = projectMetadataByPath[bucket.projectPath]?.projectType
            ?: DeclaredProjectType.OTHER
        return projectType == DeclaredProjectType.OTHER || bucket.bucketName != DEFAULT_VARIANT
    }
}
