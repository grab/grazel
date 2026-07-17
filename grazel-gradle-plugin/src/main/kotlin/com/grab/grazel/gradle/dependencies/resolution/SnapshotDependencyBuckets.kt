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
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency

/**
 * Sorts a `ProjectDependencyBucket -> (shortId -> ResolvedDependency)` map (and each of its value
 * maps) into a deterministic, reproducible order: buckets by [ProjectDependencyBucket.projectPath]
 * then [ProjectDependencyBucket.bucketName], dependencies within a bucket by their `shortId` key.
 * Shared by [DependencyBucketAccumulator]'s sorted accessors and the `declaredTestDependenciesByBucket`
 * snapshot taken for `OwnershipPlannerInput` so both call sites apply byte-identical ordering.
 */
internal fun snapshotDependencyBuckets(
    dependenciesByProjectBucket: Map<ProjectDependencyBucket, Map<String, ResolvedDependency>>
): Map<ProjectDependencyBucket, Map<String, ResolvedDependency>> {
    return dependenciesByProjectBucket.toSortedMap(
        compareBy<ProjectDependencyBucket> { bucket -> bucket.projectPath }
            .thenBy { bucket -> bucket.bucketName }
    ).mapValues { (_, dependencies) -> dependencies.toSortedMap() }
}
