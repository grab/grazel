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

import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.buildProject
import com.grab.grazel.migrate.android.AndroidLibraryData
import com.grab.grazel.migrate.android.BuildConfigData
import com.grab.grazel.migrate.android.LintConfigData
import org.gradle.api.Project
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VariantCompressorTest : GrazelPluginTest() {

    private lateinit var compressor: VariantCompressor
    private lateinit var normalizer: DependencyNormalizer
    private lateinit var checker: VariantEquivalenceChecker
    private lateinit var project: Project

    @Before
    fun setup() {
        normalizer = DefaultDependencyNormalizer()
        checker = DefaultVariantEquivalenceChecker(normalizer)
        compressor = DefaultVariantCompressor(checker)
        project = buildProject("root")
    }

    private fun createData(name: String, variantName: String = name): AndroidLibraryData {
        return AndroidLibraryData(
            name = name,
            srcs = listOf("src/main/kotlin/**/*.kt"),
            customPackage = "com.example",
            packageName = "com.example.app",
            lintConfigData = LintConfigData()
        )
    }

    private fun buildTypeFn(variantName: String): String {
        // Example: "freeDebug" -> "debug", "paidRelease" -> "release"
        return when {
            variantName.endsWith("Debug", ignoreCase = true) -> "debug"
            variantName.endsWith("Release", ignoreCase = true) -> "release"
            else -> "debug"
        }
    }

    @Test
    fun `compress empty variants returns empty result`() {
        val resultWithDecisions = compressor.compress(
            variants = emptyMap(),
            buildTypeFn = ::buildTypeFn,
            dependencyVariantCompressionResults = emptyMap()
        )

        assertTrue(resultWithDecisions.result.suffixes.isEmpty())
        assertTrue(resultWithDecisions.result.targets.isEmpty())
        assertTrue(resultWithDecisions.result.expandedBuildTypes.isEmpty())
    }

    @Test
    fun `compress single variant keeps it expanded`() {
        val variants = mapOf(
            "freeDebug" to createData("library-free-debug", "freeDebug")
        )

        val resultWithDecisions = compressor.compress(
            variants = variants,
            buildTypeFn = ::buildTypeFn,
            dependencyVariantCompressionResults = emptyMap()
        )

        assertEquals(1, resultWithDecisions.result.suffixes.size)
        assertEquals(1, resultWithDecisions.result.targets.size)
        // Single variants should NOT poison dependents - don't mark as expanded
        assertFalse(resultWithDecisions.result.expandedBuildTypes.contains("debug"))
        assertEquals("-free-debug", resultWithDecisions.result.suffixForVariant("freeDebug"))
    }

    @Test
    fun `compress equivalent variants compresses them`() {
        val variants = mapOf(
            "freeDebug" to createData("library-free-debug", "freeDebug"),
            "paidDebug" to createData("library-paid-debug", "paidDebug")
        )

        val resultWithDecisions = compressor.compress(
            variants = variants,
            buildTypeFn = ::buildTypeFn,
            dependencyVariantCompressionResults = emptyMap()
        )

        // Should compress to single "-debug" target
        assertEquals(1, resultWithDecisions.result.suffixes.size)
        assertEquals(1, resultWithDecisions.result.targets.size)
        assertTrue(resultWithDecisions.result.suffixes.contains("-debug"))
        assertFalse(resultWithDecisions.result.expandedBuildTypes.contains("debug"))

        // Both variants should map to compressed suffix
        assertEquals("-debug", resultWithDecisions.result.suffixForVariant("freeDebug"))
        assertEquals("-debug", resultWithDecisions.result.suffixForVariant("paidDebug"))
    }

    @Test
    fun `compress different variants keeps them expanded`() {
        val variants = mapOf(
            "freeDebug" to createData("library-free-debug", "freeDebug").copy(
                buildConfigData = BuildConfigData(strings = mapOf("FLAVOR" to "\"free\""))
            ),
            "paidDebug" to createData("library-paid-debug", "paidDebug").copy(
                buildConfigData = BuildConfigData(strings = mapOf("FLAVOR" to "\"paid\""))
            )
        )

        val resultWithDecisions = compressor.compress(
            variants = variants,
            buildTypeFn = ::buildTypeFn,
            dependencyVariantCompressionResults = emptyMap()
        )

        // Should keep expanded
        assertEquals(2, resultWithDecisions.result.suffixes.size)
        assertEquals(2, resultWithDecisions.result.targets.size)
        assertTrue(resultWithDecisions.result.expandedBuildTypes.contains("debug"))

        // Each variant gets its own normalized suffix
        assertEquals("-free-debug", resultWithDecisions.result.suffixForVariant("freeDebug"))
        assertEquals("-paid-debug", resultWithDecisions.result.suffixForVariant("paidDebug"))
    }

    @Test
    fun `compress multiple build types independently then fully compress if equivalent`() {
        val variants = mapOf(
            "freeDebug" to createData("library-free-debug", "freeDebug"),
            "paidDebug" to createData("library-paid-debug", "paidDebug"),
            "freeRelease" to createData("library-free-release", "freeRelease"),
            "paidRelease" to createData("library-paid-release", "paidRelease")
        )

        val resultWithDecisions = compressor.compress(
            variants = variants,
            buildTypeFn = ::buildTypeFn,
            dependencyVariantCompressionResults = emptyMap()
        )

        // Phase 1 compresses within build types, Phase 2 fully compresses across build types
        // Since all variants are equivalent, result should be single target with no suffix
        assertEquals(1, resultWithDecisions.result.suffixes.size)
        assertEquals(1, resultWithDecisions.result.targets.size)
        assertTrue(resultWithDecisions.result.suffixes.contains(""))
        assertTrue(resultWithDecisions.result.expandedBuildTypes.isEmpty())
        assertTrue(resultWithDecisions.result.isFullyCompressed)

        // All variants map to empty suffix
        assertEquals("", resultWithDecisions.result.suffixForVariant("freeDebug"))
        assertEquals("", resultWithDecisions.result.suffixForVariant("paidDebug"))
        assertEquals("", resultWithDecisions.result.suffixForVariant("freeRelease"))
        assertEquals("", resultWithDecisions.result.suffixForVariant("paidRelease"))
    }

    @Test
    fun `compress blocked by expanded dependency keeps variants expanded`() {
        val depProject = buildProject("dependency", parent = project)

        val variants = mapOf(
            "freeDebug" to createData("library-free-debug", "freeDebug").copy(
                deps = listOf(
                    BazelDependency.ProjectDependency(depProject, suffix = "-free-debug")
                )
            ),
            "paidDebug" to createData("library-paid-debug", "paidDebug").copy(
                deps = listOf(
                    BazelDependency.ProjectDependency(depProject, suffix = "-paid-debug")
                )
            )
        )

        // Dependency is expanded for "debug" build type
        val depVariantCompressionResult = VariantCompressionResult(
            targetsBySuffix = mapOf(
                "-free-debug" to createData("dependency-free-debug"),
                "-paid-debug" to createData("dependency-paid-debug")
            ),
            variantToSuffix = mapOf(
                "freeDebug" to "-free-debug",
                "paidDebug" to "-paid-debug"
            ),
            expandedBuildTypes = setOf("debug")
        )

        val resultWithDecisions = compressor.compress(
            variants = variants,
            buildTypeFn = ::buildTypeFn,
            dependencyVariantCompressionResults = mapOf(depProject to depVariantCompressionResult)
        )

        // Should be blocked from compressing
        assertEquals(2, resultWithDecisions.result.suffixes.size)
        assertTrue(resultWithDecisions.result.expandedBuildTypes.contains("debug"))
        assertEquals("-free-debug", resultWithDecisions.result.suffixForVariant("freeDebug"))
        assertEquals("-paid-debug", resultWithDecisions.result.suffixForVariant("paidDebug"))
    }

    @Test
    fun `compress mixed scenario - some compressed some expanded`() {
        val depProject = buildProject("dependency", parent = project)

        val variants = mapOf(
            // Debug variants are equivalent and not blocked
            "freeDebug" to createData("library-free-debug", "freeDebug"),
            "paidDebug" to createData("library-paid-debug", "paidDebug"),
            // Release variants differ in config
            "freeRelease" to createData("library-free-release", "freeRelease").copy(
                buildConfigData = BuildConfigData(strings = mapOf("FLAVOR" to "\"free\""))
            ),
            "paidRelease" to createData("library-paid-release", "paidRelease").copy(
                buildConfigData = BuildConfigData(strings = mapOf("FLAVOR" to "\"paid\""))
            )
        )

        val resultWithDecisions = compressor.compress(
            variants = variants,
            buildTypeFn = ::buildTypeFn,
            dependencyVariantCompressionResults = emptyMap()
        )

        // Debug should compress, Release should expand
        assertEquals(3, resultWithDecisions.result.suffixes.size)
        assertFalse(resultWithDecisions.result.expandedBuildTypes.contains("debug"))
        assertTrue(resultWithDecisions.result.expandedBuildTypes.contains("release"))

        // Debug variants compressed
        assertEquals("-debug", resultWithDecisions.result.suffixForVariant("freeDebug"))
        assertEquals("-debug", resultWithDecisions.result.suffixForVariant("paidDebug"))

        // Release variants expanded
        assertEquals("-free-release", resultWithDecisions.result.suffixForVariant("freeRelease"))
        assertEquals("-paid-release", resultWithDecisions.result.suffixForVariant("paidRelease"))
    }

    @Test
    fun `compress picks first variant alphabetically as representative`() {
        val variants = mapOf(
            "zzzDebug" to createData("library-zzz-debug", "zzzDebug").copy(
                srcs = listOf("src/main/kotlin/**/*.kt")
            ),
            "aaaDebug" to createData("library-aaa-debug", "aaaDebug").copy(
                srcs = listOf("src/main/kotlin/**/*.kt")
            ),
            "mmmDebug" to createData("library-mmm-debug", "mmmDebug").copy(
                srcs = listOf("src/main/kotlin/**/*.kt")
            )
        )

        val resultWithDecisions = compressor.compress(
            variants = variants,
            buildTypeFn = ::buildTypeFn,
            dependencyVariantCompressionResults = emptyMap()
        )

        // Should compress to single target
        assertEquals(1, resultWithDecisions.result.suffixes.size)
        val compressedData = resultWithDecisions.result.dataForSuffix("-debug")

        // Representative should be based on "aaaDebug" (first alphabetically)
        // Name should be updated to reflect compressed suffix
        assertTrue(compressedData.name.endsWith("-debug"))
    }

    @Test
    fun `compress with no dependencies`() {
        val variants = mapOf(
            "freeDebug" to createData("library-free-debug", "freeDebug").copy(
                deps = emptyList()
            ),
            "paidDebug" to createData("library-paid-debug", "paidDebug").copy(
                deps = emptyList()
            )
        )

        val resultWithDecisions = compressor.compress(
            variants = variants,
            buildTypeFn = ::buildTypeFn,
            dependencyVariantCompressionResults = emptyMap()
        )

        // Should compress successfully
        assertEquals(1, resultWithDecisions.result.suffixes.size)
        assertEquals("-debug", resultWithDecisions.result.suffixForVariant("freeDebug"))
        assertEquals("-debug", resultWithDecisions.result.suffixForVariant("paidDebug"))
    }

    @Test
    fun `compress with maven dependencies only`() {
        val mavenDep = BazelDependency.MavenDependency(
            repo = "maven",
            group = "com.google.guava",
            name = "guava"
        )

        val variants = mapOf(
            "freeDebug" to createData("library-free-debug", "freeDebug").copy(
                deps = listOf(mavenDep)
            ),
            "paidDebug" to createData("library-paid-debug", "paidDebug").copy(
                deps = listOf(mavenDep)
            )
        )

        val resultWithDecisions = compressor.compress(
            variants = variants,
            buildTypeFn = ::buildTypeFn,
            dependencyVariantCompressionResults = emptyMap()
        )

        // Maven dependencies don't block compression
        assertEquals(1, resultWithDecisions.result.suffixes.size)
        assertEquals("-debug", resultWithDecisions.result.suffixForVariant("freeDebug"))
        assertEquals("-debug", resultWithDecisions.result.suffixForVariant("paidDebug"))
    }

    @Test
    fun `full compression blocked when build types differ`() {
        val variants = mapOf(
            "freeDebug" to createData("library-free-debug", "freeDebug").copy(
                buildConfigData = BuildConfigData(strings = mapOf("BUILD_TYPE" to "\"debug\""))
            ),
            "paidDebug" to createData("library-paid-debug", "paidDebug").copy(
                buildConfigData = BuildConfigData(strings = mapOf("BUILD_TYPE" to "\"debug\""))
            ),
            "freeRelease" to createData("library-free-release", "freeRelease").copy(
                buildConfigData = BuildConfigData(strings = mapOf("BUILD_TYPE" to "\"release\""))
            ),
            "paidRelease" to createData("library-paid-release", "paidRelease").copy(
                buildConfigData = BuildConfigData(strings = mapOf("BUILD_TYPE" to "\"release\""))
            )
        )

        val resultWithDecisions = compressor.compress(
            variants = variants,
            buildTypeFn = ::buildTypeFn,
            dependencyVariantCompressionResults = emptyMap()
        )

        // Phase 1 compresses within build types, but Phase 2 is blocked because
        // debug and release targets differ in buildConfigData
        assertEquals(2, resultWithDecisions.result.suffixes.size)
        assertEquals(2, resultWithDecisions.result.targets.size)
        assertTrue(resultWithDecisions.result.suffixes.containsAll(listOf("-debug", "-release")))
        assertTrue(resultWithDecisions.result.expandedBuildTypes.isEmpty())
        assertFalse(resultWithDecisions.result.isFullyCompressed)

        assertEquals("-debug", resultWithDecisions.result.suffixForVariant("freeDebug"))
        assertEquals("-debug", resultWithDecisions.result.suffixForVariant("paidDebug"))
        assertEquals("-release", resultWithDecisions.result.suffixForVariant("freeRelease"))
        assertEquals("-release", resultWithDecisions.result.suffixForVariant("paidRelease"))
    }

    @Test
    fun `full compression blocked when dependency not fully compressed`() {
        val depProject = buildProject("dependency", parent = project)

        val variants = mapOf(
            "freeDebug" to createData("library-free-debug", "freeDebug").copy(
                deps = listOf(
                    BazelDependency.ProjectDependency(depProject, suffix = "-debug")
                )
            ),
            "paidDebug" to createData("library-paid-debug", "paidDebug").copy(
                deps = listOf(
                    BazelDependency.ProjectDependency(depProject, suffix = "-debug")
                )
            ),
            "freeRelease" to createData("library-free-release", "freeRelease").copy(
                deps = listOf(
                    BazelDependency.ProjectDependency(depProject, suffix = "-release")
                )
            ),
            "paidRelease" to createData("library-paid-release", "paidRelease").copy(
                deps = listOf(
                    BazelDependency.ProjectDependency(depProject, suffix = "-release")
                )
            )
        )

        // Dependency is NOT fully compressed (has two build type targets)
        val depVariantCompressionResult = VariantCompressionResult(
            targetsBySuffix = mapOf(
                "-debug" to createData("dependency-debug"),
                "-release" to createData("dependency-release")
            ),
            variantToSuffix = mapOf(
                "freeDebug" to "-debug",
                "paidDebug" to "-debug",
                "freeRelease" to "-release",
                "paidRelease" to "-release"
            ),
            expandedBuildTypes = emptySet()
        )

        val resultWithDecisions = compressor.compress(
            variants = variants,
            buildTypeFn = ::buildTypeFn,
            dependencyVariantCompressionResults = mapOf(depProject to depVariantCompressionResult)
        )

        // Phase 1 compresses within build types, but Phase 2 is blocked because
        // dependency is not fully compressed
        assertEquals(2, resultWithDecisions.result.suffixes.size)
        assertFalse(resultWithDecisions.result.isFullyCompressed)
        assertEquals("-debug", resultWithDecisions.result.suffixForVariant("freeDebug"))
        assertEquals("-release", resultWithDecisions.result.suffixForVariant("freeRelease"))
    }

    @Test
    fun `full compression emits FullyCompressed decision`() {
        val variants = mapOf(
            "freeDebug" to createData("library-free-debug", "freeDebug"),
            "paidDebug" to createData("library-paid-debug", "paidDebug"),
            "freeRelease" to createData("library-free-release", "freeRelease"),
            "paidRelease" to createData("library-paid-release", "paidRelease")
        )

        val resultWithDecisions = compressor.compress(
            variants = variants,
            buildTypeFn = ::buildTypeFn,
            dependencyVariantCompressionResults = emptyMap()
        )

        // Should have FullyCompressed decision
        val fullyCompressedDecision = resultWithDecisions.decisions
            .filterIsInstance<VariantCompressionDecision.FullyCompressed>()
            .firstOrNull()

        assertTrue(fullyCompressedDecision != null)
        assertTrue(fullyCompressedDecision!!.buildTypes.containsAll(listOf("debug", "release")))
        assertEquals(4, fullyCompressedDecision.variants.size)
    }

    @Test
    fun `full compression blocked when Phase 1 has expanded build types`() {
        val variants = mapOf(
            // Debug variants differ, should expand
            "freeDebug" to createData("library-free-debug", "freeDebug").copy(
                buildConfigData = BuildConfigData(strings = mapOf("FLAVOR" to "\"free\""))
            ),
            "paidDebug" to createData("library-paid-debug", "paidDebug").copy(
                buildConfigData = BuildConfigData(strings = mapOf("FLAVOR" to "\"paid\""))
            ),
            // Release variants are equivalent
            "freeRelease" to createData("library-free-release", "freeRelease"),
            "paidRelease" to createData("library-paid-release", "paidRelease")
        )

        val resultWithDecisions = compressor.compress(
            variants = variants,
            buildTypeFn = ::buildTypeFn,
            dependencyVariantCompressionResults = emptyMap()
        )

        // Phase 1 expands debug, compresses release
        // Phase 2 cannot run because expandedBuildTypes is not empty
        assertTrue(resultWithDecisions.result.expandedBuildTypes.contains("debug"))
        assertFalse(resultWithDecisions.result.isFullyCompressed)

        // Debug expanded
        assertEquals("-free-debug", resultWithDecisions.result.suffixForVariant("freeDebug"))
        assertEquals("-paid-debug", resultWithDecisions.result.suffixForVariant("paidDebug"))

        // Release compressed to build type
        assertEquals("-release", resultWithDecisions.result.suffixForVariant("freeRelease"))
        assertEquals("-release", resultWithDecisions.result.suffixForVariant("paidRelease"))
    }

    @Test
    fun `single build type with multiple variants fully compresses`() {
        val variants = mapOf(
            "freeDebug" to createData("library-free-debug", "freeDebug"),
            "paidDebug" to createData("library-paid-debug", "paidDebug")
        )

        val resultWithDecisions = compressor.compress(
            variants = variants,
            buildTypeFn = ::buildTypeFn,
            dependencyVariantCompressionResults = emptyMap()
        )

        // Only one build type, Phase 1 compresses to -debug, Phase 2 cannot run
        // (only 1 build-type target, need > 1 to fully compress)
        assertEquals(1, resultWithDecisions.result.suffixes.size)
        assertEquals("-debug", resultWithDecisions.result.suffixForVariant("freeDebug"))
        assertEquals("-debug", resultWithDecisions.result.suffixForVariant("paidDebug"))
        // isFullyCompressed is false because suffix is not empty
        assertFalse(resultWithDecisions.result.isFullyCompressed)
    }
}
