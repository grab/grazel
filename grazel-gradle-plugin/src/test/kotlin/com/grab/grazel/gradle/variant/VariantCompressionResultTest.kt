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

import com.google.common.truth.Truth.assertThat
import com.grab.grazel.migrate.android.AndroidLibraryData
import com.grab.grazel.migrate.android.LintConfigData
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VariantCompressionResultTest {

    private fun createTestData(name: String) = AndroidLibraryData(
        name = name,
        customPackage = "com.example",
        packageName = "com.example",
        lintConfigData = LintConfigData()
    )

    @Test
    fun `empty compression result has no targets or mappings`() {
        val result = VariantCompressionResult.empty()

        assertThat(result.suffixes).isEmpty()
        assertThat(result.targets).isEmpty()
        assertThat(result.variantToSuffix).isEmpty()
        assertThat(result.expandedBuildTypes).isEmpty()
    }

    @Test
    fun `compressed result maps multiple variants to single suffix`() {
        val debugData = createTestData("lib-Debug")
        val releaseData = createTestData("lib-Release")

        val result = VariantCompressionResult(
            targetsBySuffix = mapOf(
                "Debug" to debugData,
                "Release" to releaseData
            ),
            variantToSuffix = mapOf(
                "internalDebug" to "Debug",
                "prodDebug" to "Debug",
                "internalRelease" to "Release",
                "prodRelease" to "Release"
            ),
            expandedBuildTypes = emptySet()
        )

        // Verify suffixes
        assertThat(result.suffixes).containsExactly("Debug", "Release")
        assertEquals(2, result.targets.size)

        // Verify variant mappings
        assertEquals("Debug", result.suffixForVariant("internalDebug"))
        assertEquals("Debug", result.suffixForVariant("prodDebug"))
        assertEquals("Release", result.suffixForVariant("internalRelease"))
        assertEquals("Release", result.suffixForVariant("prodRelease"))

        // Verify data retrieval by suffix
        assertEquals(debugData, result.dataForSuffix("Debug"))
        assertEquals(releaseData, result.dataForSuffix("Release"))

        // Verify data retrieval by variant
        assertEquals(debugData, result.dataForVariant("internalDebug"))
        assertEquals(releaseData, result.dataForVariant("prodRelease"))
    }

    @Test
    fun `expanded build types are tracked correctly`() {
        val debugData = createTestData("lib-Debug")

        val result = VariantCompressionResult(
            targetsBySuffix = mapOf("Debug" to debugData),
            variantToSuffix = mapOf("debug" to "Debug"),
            expandedBuildTypes = setOf("release", "staging")
        )

        assertTrue(result.isExpanded("release"))
        assertTrue(result.isExpanded("staging"))
        assertFalse(result.isExpanded("debug"))
        assertFalse(result.isExpanded("nonexistent"))
    }

    @Test
    fun `validation fails when variant maps to non-existent suffix`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            VariantCompressionResult(
                targetsBySuffix = mapOf("Debug" to createTestData("lib-Debug")),
                variantToSuffix = mapOf(
                    "debug" to "Debug",
                    "release" to "Release"  // Release suffix doesn't exist
                ),
                expandedBuildTypes = emptySet()
            )
        }

        assertThat(exception.message).contains("non-existent suffixes")
        assertThat(exception.message).contains("Release")
    }

    @Test
    fun `dataForSuffix throws when suffix does not exist`() {
        val result = VariantCompressionResult(
            targetsBySuffix = mapOf("Debug" to createTestData("lib-Debug")),
            variantToSuffix = emptyMap(),
            expandedBuildTypes = emptySet()
        )

        val exception = assertFailsWith<NoSuchElementException> {
            result.dataForSuffix("Release")
        }

        assertThat(exception.message).contains("No target data found for suffix: Release")
    }

    @Test
    fun `suffixForVariant throws when variant does not exist`() {
        val result = VariantCompressionResult(
            targetsBySuffix = mapOf("Debug" to createTestData("lib-Debug")),
            variantToSuffix = mapOf("debug" to "Debug"),
            expandedBuildTypes = emptySet()
        )

        val exception = assertFailsWith<NoSuchElementException> {
            result.suffixForVariant("release")
        }

        assertThat(exception.message).contains("No suffix mapping found for variant: release")
    }

    @Test
    fun `dataForVariant throws when variant does not exist`() {
        val result = VariantCompressionResult(
            targetsBySuffix = mapOf("Debug" to createTestData("lib-Debug")),
            variantToSuffix = mapOf("debug" to "Debug"),
            expandedBuildTypes = emptySet()
        )

        val exception = assertFailsWith<NoSuchElementException> {
            result.dataForVariant("release")
        }

        assertThat(exception.message).contains("No suffix mapping found for variant: release")
    }

    @Test
    fun `targets property returns all data objects`() {
        val debugData = createTestData("lib-Debug")
        val releaseData = createTestData("lib-Release")
        val stagingData = createTestData("lib-Staging")

        val result = VariantCompressionResult(
            targetsBySuffix = mapOf(
                "Debug" to debugData,
                "Release" to releaseData,
                "Staging" to stagingData
            ),
            variantToSuffix = emptyMap(),
            expandedBuildTypes = emptySet()
        )

        assertThat(result.targets).containsExactly(debugData, releaseData, stagingData)
    }

    @Test
    fun `one-to-one mapping when no compression occurs`() {
        val internalDebugData = createTestData("lib-InternalDebug")
        val prodReleaseData = createTestData("lib-ProdRelease")

        val result = VariantCompressionResult(
            targetsBySuffix = mapOf(
                "InternalDebug" to internalDebugData,
                "ProdRelease" to prodReleaseData
            ),
            variantToSuffix = mapOf(
                "internalDebug" to "InternalDebug",
                "prodRelease" to "ProdRelease"
            ),
            expandedBuildTypes = emptySet()
        )

        assertEquals(2, result.suffixes.size)
        assertEquals(2, result.targets.size)
        assertEquals(internalDebugData, result.dataForVariant("internalDebug"))
        assertEquals(prodReleaseData, result.dataForVariant("prodRelease"))
    }
}
