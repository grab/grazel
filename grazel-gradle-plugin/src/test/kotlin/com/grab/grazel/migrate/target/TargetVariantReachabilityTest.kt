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

import com.grab.grazel.fake.FakeProject
import com.grab.grazel.fake.FakeVariant
import com.grab.grazel.fake.FakeVariantCompressionService
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.gradle.variant.VariantCompressionResult
import com.grab.grazel.gradle.variant.nameSuffix
import com.grab.grazel.migrate.android.AndroidLibraryData
import com.grab.grazel.migrate.android.LintConfigData
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class TargetVariantReachabilityTest {
    @Test
    fun `assert legacy generation includes all variants when reachability is unavailable`() {
        assertTrue {
            isReachableTargetVariant(
                variantName = "gpsPaxRelease",
                isReachableBucket = null,
            )
        }
    }

    @Test
    fun `assert generated target variants require concrete reachable variant`() {
        val reachableBuckets = setOf("gpsPaxDebug", "debug", "gps", "pax")

        assertTrue {
            isReachableTargetVariant(
                variantName = "gpsPaxDebug",
                isReachableBucket = reachableBuckets::contains,
            )
        }
        assertFalse {
            isReachableTargetVariant(
                variantName = "gpsOvoDebug",
                isReachableBucket = reachableBuckets::contains,
            )
        }
    }

    @Test
    fun `assert typed test variants are reachable through their concrete main variant`() {
        val reachableBuckets = setOf("gpsPaxDebug", "debug", "gps", "pax")

        assertTrue {
            isReachableTargetVariant(
                variantName = "gpsPaxDebugAndroidTest",
                isReachableBucket = reachableBuckets::contains,
            )
        }
        assertTrue {
            isReachableTargetVariant(
                variantName = "gpsPaxDebugUnitTest",
                isReachableBucket = reachableBuckets::contains,
            )
        }
        assertFalse {
            isReachableTargetVariant(
                variantName = "gpsOvoDebugAndroidTest",
                isReachableBucket = reachableBuckets::contains,
            )
        }
    }

    @Test
    fun `assert default bucket alone does not generate every concrete variant`() {
        val reachableBuckets = setOf("default")

        assertFalse {
            isReachableTargetVariant(
                variantName = "release",
                isReachableBucket = reachableBuckets::contains,
            )
        }
    }

    @Test
    fun `assert matched library variant reachability uses selected project variant`() {
        val reachableBuckets = setOf("debug")
        val matchedVariant = MatchedVariant(
            variantName = "demoFreeDebug",
            flavors = setOf("demo", "free"),
            buildType = "debug",
            variant = FakeVariant("debug"),
        )

        assertFalse("app leaf is not itself a reachable library bucket") {
            isReachableTargetVariant(
                variantName = matchedVariant.variantName,
                isReachableBucket = reachableBuckets::contains,
            )
        }
        assertTrue("matched project variant is reachable") {
            matchedVariant.isReachableProjectVariant(reachableBuckets::contains)
        }
    }

    @Test
    fun `assert compressed suffix is kept only when one mapped variant is reachable`() {
        val variantToSuffix = mapOf(
            "gpsPaxDebug" to "Debug",
            "gpsPaxRelease" to "Release",
            "gpsPaxStaging" to "Staging",
            "hmsPaxDebug" to "Debug",
        )

        val reachableSuffixes = reachableCompressedTargetSuffixes(
            variantToSuffix = variantToSuffix,
            reachableVariantNames = setOf("gpsPaxDebug", "hmsPaxDebug"),
        )

        assertTrue("Debug" in reachableSuffixes)
        assertFalse("Release" in reachableSuffixes)
        assertFalse("Staging" in reachableSuffixes)
    }

    @Test
    fun `assert generated target is reachable through referenced macro library target`() {
        val referencedTargetNames = setOf("ui-tests-gps-pax-debug_lib")

        assertTrue {
            isReferencedGeneratedTarget(
                targetName = "ui-tests-gps-pax-debug",
                referencedTargetNames = referencedTargetNames
            )
        }
        assertFalse {
            isReferencedGeneratedTarget(
                targetName = "ui-tests-gps-ovo-debug",
                referencedTargetNames = referencedTargetNames
            )
        }
    }

    @Test
    fun `assert variant unreachable by bucket but referenced by target name is selected`() {
        val reachableBuckets = setOf("debug")
        val matchedVariant = MatchedVariant(
            variantName = "release",
            flavors = emptySet(),
            buildType = "release",
            variant = FakeVariant("release"),
        )
        val referencedTargetNames = setOf("group-order-test-util${matchedVariant.nameSuffix}")

        assertFalse("release is not a reachable bucket") {
            matchedVariant.isReachableProjectVariant(reachableBuckets::contains)
        }
        assertTrue("but its target name is referenced by a consumer") {
            matchedVariant.isReachableProjectVariant(reachableBuckets::contains) ||
                isReferencedGeneratedTarget(
                    targetName = "group-order-test-util${matchedVariant.nameSuffix}",
                    referencedTargetNames = referencedTargetNames
                )
        }
    }

    @Test
    fun `assert variant unreachable by bucket and unreferenced is dropped`() {
        val reachableBuckets = setOf("debug")
        val matchedVariant = MatchedVariant(
            variantName = "release",
            flavors = emptySet(),
            buildType = "release",
            variant = FakeVariant("release"),
        )
        val referencedTargetNames = setOf("other-lib${matchedVariant.nameSuffix}")

        assertFalse("release is not a reachable bucket") {
            matchedVariant.isReachableProjectVariant(reachableBuckets::contains)
        }
        assertFalse("and nothing references this target name either") {
            matchedVariant.isReachableProjectVariant(reachableBuckets::contains) ||
                isReferencedGeneratedTarget(
                    targetName = "group-order-test-util${matchedVariant.nameSuffix}",
                    referencedTargetNames = referencedTargetNames
                )
        }
    }

    @Test
    fun `assert generated target is reachable through emitted macro aliases`() {
        val referencedTargetNames = setOf(
            "feature-debug_kt",
            "lib_feature-debug",
            "feature-debug_lib"
        )

        assertTrue {
            isReferencedGeneratedTarget(
                targetName = "feature-debug",
                referencedTargetNames = referencedTargetNames
            )
        }
        assertFalse {
            isReferencedGeneratedTarget(
                targetName = "feature-release",
                referencedTargetNames = referencedTargetNames
            )
        }
    }

    private fun compressionResult(suffix: String) = VariantCompressionResult(
        targetsBySuffix = mapOf(
            suffix to AndroidLibraryData(
                name = "mylib$suffix",
                customPackage = "com.example",
                packageName = "com.example",
                lintConfigData = LintConfigData()
            )
        ),
        variantToSuffix = mapOf("gpsPaxDebug" to suffix),
        expandedBuildTypes = emptySet()
    )

    @Test
    fun `assert library target unreachable by raw suffix is reachable through compressed suffix`() {
        val project = FakeProject("mylib")
        val matchedVariant = MatchedVariant(
            variantName = "gpsPaxDebug",
            flavors = setOf("gps", "pax"),
            buildType = "debug",
            variant = FakeVariant("gpsPaxDebug"),
        )
        val variantCompressionService = FakeVariantCompressionService().apply {
            register(project.path, compressionResult("-debug"))
        }
        // Raw per-variant target name is deliberately absent; only the compressed spelling is
        // referenced, reproducing the Bug-1 scenario where compression folds several flavor
        // variants into a single physical target.
        val referencedTargetNames = setOf("mylib-debug")

        assertTrue("raw target name is not directly referenced") {
            "mylib${matchedVariant.nameSuffix}" !in referencedTargetNames
        }
        assertTrue("compressed target name is reachable") {
            isReferencedGeneratedLibraryTarget(
                project = project,
                matchedVariant = matchedVariant,
                variantCompressionService = variantCompressionService,
                referencedTargetNames = referencedTargetNames
            )
        }
    }

    @Test
    fun `assert library target reachable through its raw per-variant target name`() {
        val project = FakeProject("mylib")
        val matchedVariant = MatchedVariant(
            variantName = "gpsPaxDebug",
            flavors = setOf("gps", "pax"),
            buildType = "debug",
            variant = FakeVariant("gpsPaxDebug"),
        )
        val variantCompressionService = FakeVariantCompressionService()
        val referencedTargetNames = setOf("mylib${matchedVariant.nameSuffix}")

        assertTrue("PAX still relies on the uncompressed per-variant target name resolving") {
            isReferencedGeneratedLibraryTarget(
                project = project,
                matchedVariant = matchedVariant,
                variantCompressionService = variantCompressionService,
                referencedTargetNames = referencedTargetNames
            )
        }
    }

    @Test
    fun `assert library target is unreachable when neither raw nor compressed suffix is referenced`() {
        val project = FakeProject("mylib")
        val matchedVariant = MatchedVariant(
            variantName = "gpsPaxDebug",
            flavors = setOf("gps", "pax"),
            buildType = "debug",
            variant = FakeVariant("gpsPaxDebug"),
        )
        val variantCompressionService = FakeVariantCompressionService().apply {
            register(project.path, compressionResult("-debug"))
        }
        val referencedTargetNames = setOf("other-lib-debug")

        assertFalse {
            isReferencedGeneratedLibraryTarget(
                project = project,
                matchedVariant = matchedVariant,
                variantCompressionService = variantCompressionService,
                referencedTargetNames = referencedTargetNames
            )
        }
    }

}
