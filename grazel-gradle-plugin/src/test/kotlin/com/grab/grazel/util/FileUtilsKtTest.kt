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