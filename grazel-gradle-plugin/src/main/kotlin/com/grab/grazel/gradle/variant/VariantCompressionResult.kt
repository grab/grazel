/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.grab.grazel.migrate.android.AndroidLibraryData

/**
 * Stores the result of variant compression for a single project.
 *
 * Compression maps multiple Android variants to a smaller set of Bazel targets by grouping variants
 * with identical build configuration. The result maintains:
 * - A set of unique target suffixes (e.g., "Debug", "Release", "Prod", "Internal")
 * - One AndroidLibraryData per suffix (1:1 mapping)
 * - A mapping from each variant name to its target suffix (many:1 mapping)
 * - A set of build types that should remain expanded (not compressed)
 *
 * @property targetsBySuffix Map from target suffix to AndroidLibraryData (1:1)
 * @property variantToSuffix Map from variant name to target suffix (many:1)
 * @property expandedBuildTypes Set of build type names that should remain expanded
 */
internal data class VariantCompressionResult(
    val targetsBySuffix: Map<String, AndroidLibraryData>,
    val variantToSuffix: Map<String, String>,
    val expandedBuildTypes: Set<String>
) {
    init {
        // Validate that all suffix references in variantToSuffix exist in targetsBySuffix
        val missingSuffixes = variantToSuffix.values.toSet() - targetsBySuffix.keys
        require(missingSuffixes.isEmpty()) {
            "Variant mappings reference non-existent suffixes: $missingSuffixes"
        }
    }

    /** Returns the set of all target suffixes. */
    val suffixes: Set<String>
        get() = targetsBySuffix.keys

    /**
     * Returns true if this project is fully compressed (single target with no suffix).
     *
     * Full compression occurs when:
     * - There is exactly one target
     * - That target has an empty suffix (no build-type suffix)
     * - No build types are expanded
     */
    val isFullyCompressed: Boolean
        get() = targetsBySuffix.size == 1 &&
            expandedBuildTypes.isEmpty() &&
            targetsBySuffix.keys.singleOrNull() == ""

    /** Returns the list of all target data objects. */
    val targets: List<AndroidLibraryData>
        get() = targetsBySuffix.values.toList()

    /**
     * Returns the AndroidLibraryData for the given suffix.
     *
     * @throws NoSuchElementException if suffix does not exist
     */
    fun dataForSuffix(suffix: String): AndroidLibraryData {
        return targetsBySuffix[suffix]
            ?: throw NoSuchElementException("No target data found for suffix: $suffix")
    }

    /**
     * Returns the AndroidLibraryData for the given variant name.
     *
     * @throws NoSuchElementException if variant does not exist or its suffix is not found
     */
    fun dataForVariant(variantName: String): AndroidLibraryData {
        val suffix = suffixForVariant(variantName)
        return dataForSuffix(suffix)
    }

    /**
     * Returns the target suffix for the given variant name.
     *
     * @throws NoSuchElementException if variant does not exist
     */
    fun suffixForVariant(variantName: String): String {
        return variantToSuffix[variantName]
            ?: throw NoSuchElementException("No suffix mapping found for variant: $variantName")
    }

    /** Returns the target suffix for the given variant name, or null if not found. */
    fun suffixForVariantOrNull(variantName: String): String? {
        return variantToSuffix[variantName]
    }

    /** Returns true if the given build type should remain expanded (not compressed). */
    fun isExpanded(buildType: String): Boolean {
        return buildType in expandedBuildTypes
    }

    companion object Companion {
        /** Returns an empty VariantCompressionResult with no targets or mappings. */
        fun empty(): VariantCompressionResult = VariantCompressionResult(
            targetsBySuffix = emptyMap(),
            variantToSuffix = emptyMap(),
            expandedBuildTypes = emptySet()
        )
    }
}
