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

package com.grab.grazel.gradle.variant

import com.grab.grazel.fake.FakeProject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class VariantGraphKeyTest {

    @Test
    fun `from Project and variantName should create correct key`() {
        val project = FakeProject("app")
        val key = VariantGraphKey.from(project, "debug", VariantType.AndroidBuild)

        assertEquals(":app:debugAndroidBuild", key.variantId)
        assertEquals(VariantType.AndroidBuild, key.variantType)
    }

    @Test
    fun `variantType field should be accessible directly`() {
        val key = VariantGraphKey(":app:debugAndroidBuild", VariantType.AndroidBuild)
        assertEquals(VariantType.AndroidBuild, key.variantType)
    }

    @Test
    fun `keys with same variantId but different variantType should not be equal`() {
        val key1 = VariantGraphKey(":app:debug", VariantType.AndroidBuild)
        val key2 = VariantGraphKey(":app:debug", VariantType.Test)

        assertNotEquals(key1, key2)
    }

    @Test
    fun `keys with different variantId but same variantType should not be equal`() {
        val key1 = VariantGraphKey(":app:debug", VariantType.AndroidBuild)
        val key2 = VariantGraphKey(":app:release", VariantType.AndroidBuild)

        assertNotEquals(key1, key2)
    }

    @Test
    fun `keys with same variantId and variantType should be equal`() {
        val key1 = VariantGraphKey(":app:debug", VariantType.AndroidBuild)
        val key2 = VariantGraphKey(":app:debug", VariantType.AndroidBuild)

        assertEquals(key1, key2)
    }

    @Test
    fun `isBuildGraph should return true for AndroidBuild`() {
        val key = VariantGraphKey(":app:debug", VariantType.AndroidBuild)
        assert(key.variantType.isBuildGraph)
    }

    @Test
    fun `isBuildGraph should return true for JvmBuild`() {
        val key = VariantGraphKey(":lib:default", VariantType.JvmBuild)
        assert(key.variantType.isBuildGraph)
    }

    @Test
    fun `isBuildGraph should return false for Test`() {
        val key = VariantGraphKey(":lib:test", VariantType.Test)
        assert(!key.variantType.isBuildGraph)
    }

    @Test
    fun `isBuildGraph should return false for AndroidTest`() {
        val key = VariantGraphKey(":app:debugAndroidTest", VariantType.AndroidTest)
        assert(!key.variantType.isBuildGraph)
    }

    @Test
    fun `from Project and variantName should create correct key for AndroidBuild`() {
        val project = FakeProject("sample")
        val key = VariantGraphKey.from(project, "debug", VariantType.AndroidBuild)

        assertEquals(":sample:debugAndroidBuild", key.variantId)
        assertEquals(VariantType.AndroidBuild, key.variantType)
    }

    @Test
    fun `from Project and variantName should create correct key for Test`() {
        val project = FakeProject("sample")
        val key = VariantGraphKey.from(project, "debugUnitTest", VariantType.Test)

        assertEquals(":sample:debugUnitTestTest", key.variantId)
        assertEquals(VariantType.Test, key.variantType)
    }

    @Test
    fun `from Project and variantName should create correct key for AndroidTest`() {
        val project = FakeProject("sample")
        val key = VariantGraphKey.from(project, "debugAndroidTest", VariantType.AndroidTest)

        assertEquals(":sample:debugAndroidTestAndroidTest", key.variantId)
        assertEquals(VariantType.AndroidTest, key.variantType)
    }

    @Test
    fun `from Project and variantName should create correct key for JvmBuild`() {
        val project = FakeProject("lib")
        val key = VariantGraphKey.from(project, "default", VariantType.JvmBuild)

        assertEquals(":lib:defaultJvmBuild", key.variantId)
        assertEquals(VariantType.JvmBuild, key.variantType)
    }

    @Test
    fun `from Project and variantName should preserve variant name exactly`() {
        val project = FakeProject("app")
        val key = VariantGraphKey.from(project, "freeDebug", VariantType.AndroidBuild)

        assertEquals(":app:freeDebugAndroidBuild", key.variantId)
        assertEquals(VariantType.AndroidBuild, key.variantType)
    }

    @Test
    fun `data class copy should work correctly`() {
        val original = VariantGraphKey(":app:debug", VariantType.AndroidBuild)
        val copy = original.copy(variantId = ":app:release")

        assertEquals(":app:release", copy.variantId)
        assertEquals(VariantType.AndroidBuild, copy.variantType)
        assertNotEquals(original, copy)
    }

    @Test
    fun `data class hashCode should depend on both fields`() {
        val key1 = VariantGraphKey(":app:debug", VariantType.AndroidBuild)
        val key2 = VariantGraphKey(":app:debug", VariantType.AndroidBuild)
        val key3 = VariantGraphKey(":app:debug", VariantType.Test)

        assertEquals(key1.hashCode(), key2.hashCode())
        assertNotEquals(key1.hashCode(), key3.hashCode())
    }

    @Test
    fun `variantType field should be non-nullable and always present`() {
        val key = VariantGraphKey(":app:debug", VariantType.AndroidBuild)
        // This test primarily documents the fact that variantType is non-nullable
        // If we could access null, this would fail at compile time
        val type: VariantType = key.variantType // No null check needed
        assertEquals(VariantType.AndroidBuild, type)
    }
}
