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

import com.grab.grazel.fake.FakeVariant
import com.grab.grazel.gradle.variant.MatchedVariant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class TargetVariantReachabilityTest {
    @Test
    fun `assert legacy generation includes all variants when reachability is unavailable`() {
        assertTrue {
            isReachableTargetVariant(
                variantName = "gpsPaxRelease",
                buildType = "release",
                flavors = setOf("gps", "pax"),
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
                buildType = "debug",
                flavors = setOf("gps", "pax"),
                isReachableBucket = reachableBuckets::contains,
            )
        }
        assertFalse {
            isReachableTargetVariant(
                variantName = "gpsOvoDebug",
                buildType = "debug",
                flavors = setOf("gps", "ovo"),
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
                buildType = "debug",
                flavors = setOf("gps", "pax"),
                isReachableBucket = reachableBuckets::contains,
            )
        }
        assertTrue {
            isReachableTargetVariant(
                variantName = "gpsPaxDebugUnitTest",
                buildType = "debug",
                flavors = setOf("gps", "pax"),
                isReachableBucket = reachableBuckets::contains,
            )
        }
        assertFalse {
            isReachableTargetVariant(
                variantName = "gpsOvoDebugAndroidTest",
                buildType = "debug",
                flavors = setOf("gps", "ovo"),
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
                buildType = "release",
                flavors = emptySet(),
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
                buildType = matchedVariant.buildType,
                flavors = matchedVariant.flavors,
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
}
