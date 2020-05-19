/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
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