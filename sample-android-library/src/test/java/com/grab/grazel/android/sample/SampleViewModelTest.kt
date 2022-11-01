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

package com.grab.grazel.android.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

class SampleViewModelTest {

    @Test
    fun testParcelable() {
        val viewModel = SampleViewModel()
        assertEquals("tom", viewModel.user.firstName)
    }

    @Test
    fun testLoadResources() {
        val testJson = read("test.json")
        assertTrue(testJson.isNotBlank())
        val mockResponse = read("mock-response.json")
        assertTrue(mockResponse.isNotBlank())
    }

    private fun read(resourceName: String): String {
        val sb = StringBuilder()
        try {
            val reader = BufferedReader(
                InputStreamReader(
                    this::class.java.classLoader!!.getResourceAsStream(resourceName), "UTF-8"
                )
            )
            var strLine = reader.readLine()
            while (strLine != null) {
                sb.append(strLine)
                strLine = reader.readLine()
            }
            reader.close()
        } catch (ex: IOException) {
            throw IllegalStateException(ex)
        }
        return sb.toString()
    }
}
