/*
 * Copyright 2021 Grabtaxi Holdings PTE LTE (GRAB)
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

package com.grab.grazel.hybrid

import org.junit.Assert.assertTrue
import org.junit.Test

class HybridKtTest {

    @Test
    fun `when multiple android library targets are there assert android_library targets are calculated`() {
        val allAarTargets = sequenceOf(
            "//base/target", // android_library
            "//base/another_target", // kt_android_library
            "//base/another_target_base" // kt_android_library_base
        )

        val uniqueTargets = findUniqueAarTargets(aarTargets = allAarTargets)
        assertTrue(uniqueTargets.size == 1)
        assertTrue(uniqueTargets.first() == "//base/target.aar")
    }
}