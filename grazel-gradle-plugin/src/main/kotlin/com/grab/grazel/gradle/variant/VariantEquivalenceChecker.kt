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

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.migrate.android.AndroidLibraryData
import javax.inject.Inject

/**
 * Checks if two [AndroidLibraryData] instances are equivalent for variant compression.
 *
 * Two variants are considered equivalent if they have identical:
 * - Source files
 * - Resource sets
 * - Manifest files
 * - Package names
 * - Build config
 * - Resource values
 * - Dependencies (after normalization)
 *
 * Project-level properties (databinding, compose, plugins, lint config) are excluded since they
 * don't vary by variant.
 */
internal interface VariantEquivalenceChecker {
    /**
     * Checks if two AndroidLibraryData instances are equivalent for compression.
     *
     * @param first First variant data to compare
     * @param second Second variant data to compare
     * @return true if variants are equivalent and can be compressed together
     */
    fun areEquivalent(first: AndroidLibraryData, second: AndroidLibraryData): Boolean
}

internal class DefaultVariantEquivalenceChecker @Inject constructor(
    private val dependencyNormalizer: DependencyNormalizer
) : VariantEquivalenceChecker {

    override fun areEquivalent(first: AndroidLibraryData, second: AndroidLibraryData): Boolean {
        // Compare variant-specific fields
        return first.srcs == second.srcs &&
            first.resourceSets == second.resourceSets &&
            first.manifestFile == second.manifestFile &&
            first.packageName == second.packageName &&
            first.customPackage == second.customPackage &&
            first.buildConfigData == second.buildConfigData &&
            first.resValuesData == second.resValuesData &&
            areDepsEquivalent(first.deps, second.deps)
    }

    /**
     * Compares two dependency lists for equivalence after normalization.
     *
     * Dependencies are normalized to remove variant-specific suffixes, then sorted and compared for
     * equality.
     */
    private fun areDepsEquivalent(
        firstDeps: List<BazelDependency>,
        secondDeps: List<BazelDependency>
    ): Boolean {
        if (firstDeps.size != secondDeps.size) {
            return false
        }

        val firstNormalized = firstDeps
            .map { dependencyNormalizer.normalize(it) }
            .sorted()

        val secondNormalized = secondDeps
            .map { dependencyNormalizer.normalize(it) }
            .sorted()

        return firstNormalized == secondNormalized
    }
}
