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

package com.grab.grazel.gradle.dependencies

import com.grab.grazel.gradle.dependencies.model.ExcludeRule
import com.grab.grazel.gradle.variant.VariantType
import kotlinx.serialization.Serializable
import org.gradle.api.artifacts.result.ResolvedComponentResult
import java.io.Serializable as JavaSerializable

internal data class AggregatedDependencyRoot(
    val root: ResolvedComponentResult,
    val metadata: AggregatedDependencyRootMetadata
)

@Serializable
internal data class AggregatedDependencyRootMetadata(
    val projectPath: String,
    val kind: AggregatedDependencyRootKind,
    val configurationName: String,
    val bucketName: String? = null,
    val leafName: String? = null,
    val variantNames: Set<String> = emptySet(),
    val variantType: VariantType? = null,
    val buildType: String? = null,
    val productFlavors: List<String> = emptyList(),
    val targetBuckets: Set<String> = emptySet(),
    val traverseProjectNodes: Boolean = true,
    val directDependencyShortIds: Set<String> = emptySet(),
    val rootExcludeRulesByShortId: Map<String, Set<ExcludeRule>> = emptyMap()
) : JavaSerializable

@Serializable
internal enum class AggregatedDependencyRootKind {
    MAIN_HIERARCHY,
    MAIN_LEAF,
    TEST_HIERARCHY,
    UNIT_TEST,
    ANDROID_TEST,
    LINT
}

internal fun ResolvedComponentResult.isBomComponent(): Boolean {
    val version = moduleVersion ?: return false
    return version.name.endsWith("-bom", ignoreCase = true) ||
        version.name.endsWith(".bom", ignoreCase = true)
}
