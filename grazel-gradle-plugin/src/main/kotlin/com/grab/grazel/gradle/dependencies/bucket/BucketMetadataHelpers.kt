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

/**
 * Helpers shared by both main-bucket and test-bucket ownership planning: reconciling user-declared
 * dependency metadata (excludes, overrides) back onto placed buckets, and merging per-bucket
 * dependency maps without losing already-recorded metadata.
 */
package com.grab.grazel.gradle.dependencies.bucket

import com.grab.grazel.gradle.dependencies.mergeBucket
import com.grab.grazel.gradle.dependencies.mergeDependencyMetadataByMaxVersion
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency

internal fun addDeclaredOutputMetadata(
    declaredMetadataByOutputBucket: MutableMap<String, Map<String, ResolvedDependency>>,
    bucketName: String,
    metadata: Map<String, ResolvedDependency>
) {
    if (metadata.isEmpty()) return
    declaredMetadataByOutputBucket.mergeBucket(bucketName, metadata)
}

internal fun applyDeclaredMetadataByBucket(
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

internal fun applyDeclaredMetadata(
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
