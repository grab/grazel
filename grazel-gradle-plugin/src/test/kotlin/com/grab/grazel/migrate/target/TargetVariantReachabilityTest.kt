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
    fun `assert generated target variants match concrete reachable buckets`() {
        val reachableBuckets = setOf("debug", "gps")

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
                variantName = "paxRelease",
                buildType = "release",
                flavors = setOf("pax"),
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
}
