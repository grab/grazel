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

package com.grab.grazel.util

import com.grab.grazel.fake.FakeProgressLoggerFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class ProgressLoggerTest {

    @Test
    fun `withProgress forwards reports and completes operation`() {
        val factory = FakeProgressLoggerFactory()

        val result = factory.withProgress("working") { reporter ->
            reporter.report("step 1")
            reporter.report("step 2")
            "done"
        }

        assertEquals("done", result)
        val operation = factory.operations.single()
        assertTrue(operation.started)
        assertTrue(operation.completed)
        assertEquals("working", operation.desc)
        assertEquals(listOf("step 1", "step 2"), operation.progressMessages)
    }

    @Test
    fun `withProgress completes operation when block fails`() {
        val factory = FakeProgressLoggerFactory()

        assertFailsWith<IllegalStateException> {
            factory.withProgress("working") {
                throw IllegalStateException("boom")
            }
        }

        assertTrue(factory.operations.single().completed)
    }
}
