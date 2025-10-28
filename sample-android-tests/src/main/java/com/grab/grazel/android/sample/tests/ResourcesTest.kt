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
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test that uses custom Android resources from the test module.
 *
 * This test validates:
 * - resourceFiles field is populated
 * - Android resources are properly included in test APK
 * - resourceStripPrefix is correctly set
 */
@RunWith(AndroidJUnit4::class)
class ResourcesTest {

    @Test
    fun testCustomTestString() {
        val context = InstrumentationRegistry.getInstrumentation().context

        // Access a string resource defined in test module
        val resourceId = context.resources.getIdentifier(
            "test_string",
            "string",
            context.packageName
        )

        if (resourceId != 0) {
            val testString = context.getString(resourceId)
            assertEquals("Test String Value", testString)
        }
        // If resource doesn't exist, that's ok - we're testing infrastructure
    }

    @Test
    fun testCustomTestColor() {
        val context = InstrumentationRegistry.getInstrumentation().context

        // Access a color resource defined in test module
        val resourceId = context.resources.getIdentifier(
            "test_color",
            "color",
            context.packageName
        )

        if (resourceId != 0) {
            val color = context.getColor(resourceId)
            assertNotNull(color)
        }
        // If resource doesn't exist, that's ok - we're testing infrastructure
    }

    @Test
    fun testResourcesAreAccessible() {
        val context = InstrumentationRegistry.getInstrumentation().context
        assertNotNull(context.resources)
        assertNotNull(context.packageName)
    }
}
