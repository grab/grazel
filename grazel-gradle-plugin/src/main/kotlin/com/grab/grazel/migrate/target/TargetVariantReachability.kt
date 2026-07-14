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
import com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanService
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.util.GradleProvider
import org.gradle.api.Project

internal fun BaseVariant.isReachableTargetVariant(
    isReachableBucket: ((String) -> Boolean)?
): Boolean = isReachableTargetVariant(
    variantName = name,
    isReachableBucket = isReachableBucket,
)

internal fun MatchedVariant.isReachableProjectVariant(
    isReachableBucket: ((String) -> Boolean)?
): Boolean = variant.isReachableTargetVariant(isReachableBucket)

/**
 * Checks reachability against the exact variant name first, then - if that fails - retries
 * against the name with its `AndroidTest`/`UnitTest` suffix stripped ([removeTypedTestSuffix]).
 * This fallback is load-bearing: typed test variants (e.g. `debugAndroidTest`) are not themselves
 * declaration buckets, so their reachability has to be inferred from the base build variant
 * (`debug`) they were generated from.
 */
internal fun isReachableTargetVariant(
    variantName: String,
    isReachableBucket: ((String) -> Boolean)?
): Boolean {
    if (isReachableBucket == null) {
        return true
    }
    return isReachableBucket(variantName) ||
        variantName.removeTypedTestSuffix()
            ?.let(isReachableBucket)
            ?: false
}

private fun String.removeTypedTestSuffix(): String? {
    val mainVariantName = when {
        endsWith("AndroidTest") -> removeSuffix("AndroidTest")
        endsWith("UnitTest") -> removeSuffix("UnitTest")
        else -> null
    }
    return mainVariantName?.takeUnless(String::isBlank)
}

internal fun reachableCompressedTargetSuffixes(
    variantToSuffix: Map<String, String>,
    reachableVariantNames: Set<String>
): Set<String> = variantToSuffix
    .asSequence()
    .filter { (variantName, _) -> variantName in reachableVariantNames }
    .map { (_, suffix) -> suffix }
    .toSet()

/**
 * Bazel macros (e.g. Kotlin/Android library macros) can expand a single logical target into
 * several physical target names (`_lib`, `_kt`, `lib_` prefixed variants in addition to the bare
 * name). A reference to any one of these variant spellings counts as a reference to the logical
 * target, so all must be checked; omitting one here would make a target whose only reference is
 * through that spelling look unreachable and get dropped.
 */
internal fun isReferencedGeneratedTarget(
    targetName: String,
    referencedTargetNames: Set<String>
): Boolean {
    val macroTargetNames = setOf(
        targetName,
        "${targetName}_lib",
        "${targetName}_kt",
        "lib_$targetName"
    )
    return macroTargetNames.any { candidate -> candidate in referencedTargetNames }
}

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

/**
 * A Kotlin project's facts are collected if any of three independent signals hold, checked with
 * OR short-circuit in this order: (1) no bucket-reachability data exists at all for the workspace
 * (nothing to filter by, so default to including it), (2) its default-variant bucket is directly
 * reachable, or (3) the render plan already recorded a reference to this project path from
 * elsewhere. The ordering matters for cost (bucket data absence is cheapest to check) but not for
 * correctness; however, all three must be considered since dropping any one can make an otherwise
 * legitimately-referenced Kotlin project silently skip fact collection entirely.
 */
internal fun isReachableJvmProject(
    project: Project,
    dependencyResolutionService: GradleProvider<DefaultDependencyResolutionService>,
    workspaceRenderPlanService: GradleProvider<WorkspaceRenderPlanService>
): Boolean {
    val service = dependencyResolutionService.get()
    return !service.hasMainBucketReachability() ||
        service.isReachableMainBucket(project.path, DEFAULT_VARIANT) ||
        workspaceRenderPlanService.get().isReferencedProjectPath(project.path)
}
