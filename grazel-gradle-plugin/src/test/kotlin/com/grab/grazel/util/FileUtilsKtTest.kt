/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.util

import org.junit.Assert
import org.junit.Test

class FileUtilsKtTest {
    @Test
    fun `when multiple paths are given assert common path is calculated`() {
        val paths = arrayOf(
            "/home/src/main/java/com/grab/package1",
            "/home/src/main/java/com/grab/package2",
            "/home/src/main/java/com/grab/package3",
            "/home/src/main/java/com/grab/package1/",
            "/home/src/main/java/com/grab/package1/sub"
        )
        val commonPath = commonPath(*paths)
        Assert.assertTrue(commonPath == "/home/src/main/java/com/grab/")
    }
}