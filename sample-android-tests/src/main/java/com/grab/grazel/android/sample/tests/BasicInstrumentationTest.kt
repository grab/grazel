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
package com.grab.grazel.android.sample.tests

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Basic instrumentation test that validates test infrastructure.
 *
 * This test demonstrates:
 * - Basic test execution
 * - Access to Android context
 * - Test runner configuration
 */
@RunWith(AndroidJUnit4::class)
class BasicInstrumentationTest {

    @Test
    fun testAppContext() {
        // Context of the app under test
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // Package name includes flavor suffix (e.g., .free, .paid)
        assert(appContext.packageName.startsWith("com.grab.grazel.android.sample"))
    }

    @Test
    fun testTestContext() {
        // Context of the test app
        val testContext = InstrumentationRegistry.getInstrumentation().context
        assertEquals("com.grab.grazel.android.sample.tests", testContext.packageName)
    }

    @Test
    fun testInstrumentationRunner() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("androidx.test.runner.AndroidJUnitRunner",
            instrumentation.javaClass.name)
    }
}
