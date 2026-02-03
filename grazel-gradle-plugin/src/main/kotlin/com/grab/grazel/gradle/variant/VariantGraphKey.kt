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

package com.grab.grazel.gradle.variant

import org.gradle.api.Project

/**
 * Key for dependency graphs using Variant's unique identifier.
 *
 * The key includes the project path to ensure different projects' variants with the same name are
 * stored in separate graphs.
 *
 * @property variantId Unique ID in format "projectPath:variantName" + "VariantType" (e.g.,
 *    ":sample-android:debugAndroidBuild")
 * @property variantType The type of variant this key represents (AndroidBuild, Test, etc.)
 */
data class VariantGraphKey(
    val variantId: String,
    val variantType: VariantType
) {
    companion object {
        /** Create from a Variant instance using its unique ID. */
        fun from(variant: Variant<*>): VariantGraphKey =
            VariantGraphKey(
                variantId = variant.project.path + ":" + variant.id,
                variantType = variant.variantType
            )

        /**
         * Create from Project + MatchedVariant + VariantType. Uses the full variant name (e.g.,
         * "debugUnitTest") not the cleaned name (e.g., "debug") to match the keys built by
         * DependenciesGraphsBuilder.
         */
        internal fun from(
            project: Project,
            matchedVariant: MatchedVariant,
            variantType: VariantType
        ): VariantGraphKey =
            VariantGraphKey(
                variantId = project.path + ":" + matchedVariant.variant.name + variantType.toString(),
                variantType = variantType
            )

        /** Create from Project + variant name + VariantType. Used during graph building. */
        internal fun from(
            project: Project,
            variantName: String,
            variantType: VariantType
        ): VariantGraphKey =
            VariantGraphKey(
                variantId = project.path + ":" + variantName + variantType.toString(),
                variantType = variantType
            )
    }
}
