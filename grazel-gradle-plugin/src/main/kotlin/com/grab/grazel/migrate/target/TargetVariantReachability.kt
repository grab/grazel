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

package com.grab.grazel.migrate.target

import com.android.build.gradle.api.BaseVariant
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.util.GradleProvider
import org.gradle.api.Project

internal fun BaseVariant.isReachableTargetVariant(
    isReachableBucket: ((String) -> Boolean)?
): Boolean = isReachableTargetVariant(
    variantName = name,
    buildType = buildType.name,
    flavors = productFlavors.mapTo(mutableSetOf()) { it.name },
    isReachableBucket = isReachableBucket,
)

internal fun isReachableTargetVariant(
    variantName: String,
    buildType: String,
    flavors: Set<String>,
    isReachableBucket: ((String) -> Boolean)?
): Boolean {
    if (isReachableBucket == null) {
        return true
    }
    return isReachableBucket(variantName) ||
        isReachableBucket(buildType) ||
        flavors.any(isReachableBucket)
}

internal fun reachableCompressedTargetSuffixes(
    variantToSuffix: Map<String, String>,
    reachableVariantNames: Set<String>
): Set<String> = variantToSuffix
    .asSequence()
    .filter { (variantName, _) -> variantName in reachableVariantNames }
    .map { (_, suffix) -> suffix }
    .toSet()

internal fun reachableBucketPredicate(
    project: Project,
    dependencyResolutionService: GradleProvider<DefaultDependencyResolutionService>
): ((String) -> Boolean)? {
    val service = dependencyResolutionService.get()
    return if (service.hasMainBucketReachability()) {
        { bucketName -> service.isReachableMainBucket(project.path, bucketName) }
    } else {
        null
    }
}
