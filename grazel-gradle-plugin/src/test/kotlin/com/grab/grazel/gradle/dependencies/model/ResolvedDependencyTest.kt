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

package com.grab.grazel.gradle.dependencies.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedDependencyTest {
    @Test
    fun `from parses dependency notation with jetifier source`() {
        val dependency = ResolvedDependency.from(
            "androidx.legacy:legacy-support-v4:1.0.0:maven:true:com.android.support:support-v4"
        )

        assertEquals("androidx.legacy:legacy-support-v4:1.0.0", dependency.id)
        assertEquals("androidx.legacy:legacy-support-v4", dependency.shortId)
        assertEquals("1.0.0", dependency.version)
        assertEquals("maven", dependency.repository)
        assertTrue(dependency.requiresJetifier)
        assertEquals("com.android.support:support-v4", dependency.jetifierSource)
    }
}
