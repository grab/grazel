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

import com.grab.grazel.gradle.dependencies.ProjectDependencyBucket
import com.grab.grazel.gradle.dependencies.bucket.mergeBucket
import com.grab.grazel.gradle.dependencies.bucket.unionDependencyMaps
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency

/**
 * Which closure map a [DependencyBucketAccumulator.fold] call targets, replacing the inline
 * "which map" decision `collectRootClosures` used to make in `AggregatedDependencyResolver`.
 */
internal enum class BucketTarget { HIERARCHY, TEST_HIERARCHY, LEAF, LEAF_UNIT_TEST, LEAF_ANDROID_TEST }

/**
 * Accumulates the closure-bucket maps populated while walking workspace dependency roots.
 * Relocated verbatim from `AggregatedDependencyResolver.ResolutionSession` (behaviour unchanged)
 * as part of decomposing that god-class: holds the five `*Closures` maps plus [lintDeps], and
 * exposes sorted snapshot accessors ([leaf], [leafUnitTest], [leafAndroidTest], [hierarchy],
 * [testHierarchy]) matching the sort order `snapshotDependencyBuckets` previously applied at the
 * `OwnershipPlannerInput` call site.
 */
internal class DependencyBucketAccumulator {
    private val leafClosures = mutableMapOf<ProjectDependencyBucket, Map<String, ResolvedDependency>>()
    private val leafUnitTestClosures = mutableMapOf<ProjectDependencyBucket, Map<String, ResolvedDependency>>()
    private val leafAndroidTestClosures = mutableMapOf<ProjectDependencyBucket, Map<String, ResolvedDependency>>()
    private val hierarchyBucketClosures = mutableMapOf<ProjectDependencyBucket, Map<String, ResolvedDependency>>()
    private val testHierarchyBucketClosures = mutableMapOf<ProjectDependencyBucket, Map<String, ResolvedDependency>>()
    private var mutableLintDeps = emptyMap<String, ResolvedDependency>()

    val lintDeps: Map<String, ResolvedDependency> get() = mutableLintDeps

    fun fold(
        target: BucketTarget,
        projectPath: String,
        bucketName: String,
        closure: Map<String, ResolvedDependency>,
        keepEmpty: Boolean = true
    ) {
        if (!keepEmpty && closure.isEmpty()) return
        val dependenciesByProjectBucket = when (target) {
            BucketTarget.HIERARCHY -> hierarchyBucketClosures
            BucketTarget.TEST_HIERARCHY -> testHierarchyBucketClosures
            BucketTarget.LEAF -> leafClosures
            BucketTarget.LEAF_UNIT_TEST -> leafUnitTestClosures
            BucketTarget.LEAF_ANDROID_TEST -> leafAndroidTestClosures
        }
        dependenciesByProjectBucket.mergeBucket(ProjectDependencyBucket(projectPath, bucketName), closure)
    }

    fun foldLint(closure: Map<String, ResolvedDependency>) {
        mutableLintDeps = unionDependencyMaps(mutableLintDeps, closure)
    }

    fun hasResolvedClosures(): Boolean {
        return leafClosures.isNotEmpty() ||
            hierarchyBucketClosures.isNotEmpty() ||
            leafUnitTestClosures.isNotEmpty() ||
            leafAndroidTestClosures.isNotEmpty() ||
            testHierarchyBucketClosures.isNotEmpty()
    }

    fun leaf(): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> =
        snapshotDependencyBuckets(leafClosures)

    fun leafUnitTest(): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> =
        snapshotDependencyBuckets(leafUnitTestClosures)

    fun leafAndroidTest(): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> =
        snapshotDependencyBuckets(leafAndroidTestClosures)

    fun hierarchy(): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> =
        snapshotDependencyBuckets(hierarchyBucketClosures)

    fun testHierarchy(): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> =
        snapshotDependencyBuckets(testHierarchyBucketClosures)

    private fun snapshotDependencyBuckets(
        dependenciesByProjectBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
    ): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
        return dependenciesByProjectBucket.toSortedMap(
            compareBy<ProjectDependencyBucket> { bucket -> bucket.projectPath }
                .thenBy { bucket -> bucket.bucketName }
        ).mapValues { (_, dependencies) -> dependencies.toSortedMap() }
    }
}
