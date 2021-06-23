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

package com.grab.grazel.gradle

import com.android.build.gradle.api.BaseVariant
import com.android.builder.model.ProductFlavor
import com.grab.grazel.GrazelExtension
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.buildProject
import com.grab.grazel.util.*
import org.gradle.api.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAndroidBuildVariantDataSourceTest : GrazelPluginTest() {
    private val project = buildProject("App")
    private val extension = GrazelExtension(project)
    private val fakeVariantsExtractor = FakeAndroidVariantsExtractor()
    private val buildVariantDataSource = DefaultAndroidBuildVariantDataSource(fakeVariantsExtractor)

    @Test
    fun `when config to ignore variant, assert the related flavors also be ignored`() {
        val ignoreVariants = listOf(DEBUG_FLAVOR1, DEBUG_FLAVOR1, RELEASE_FLAVOR1)
        extension.androidConfiguration.variantFilter {
            if (name in ignoreVariants) setIgnore(true)
        }
        val ignoreFlavors = DefaultAndroidBuildVariantDataSource(
            fakeVariantsExtractor,
            extension.androidConfiguration.variantFilter
        ).getIgnoredFlavors(project)
        assertEquals(1, ignoreFlavors.size)
        assertEquals(FLAVOR1, ignoreFlavors[0].name)
    }

    @Test
    fun `when no filter applied, assert ignore flavor return empty list`() {
        val ignoreFlavors = buildVariantDataSource.getIgnoredFlavors(project)
        assertEquals(0, ignoreFlavors.size)
    }

    @Test
    fun `when no variants filter applied, assert ignored variants should return emtpy list`() {
        val ignoreVariants = buildVariantDataSource.getIgnoredVariants(project)
        assertEquals(0, ignoreVariants.size)
    }


    @Test
    fun `when variants filter applied, assert ignored variants should be returned`() {
        val ignoreVariants = listOf(DEBUG_FLAVOR1, DEBUG_FLAVOR1, RELEASE_FLAVOR1)
        extension.androidConfiguration.variantFilter {
            if (name in ignoreVariants) setIgnore(true)
        }
        DefaultAndroidBuildVariantDataSource(
            fakeVariantsExtractor,
            extension.androidConfiguration.variantFilter
        ).getIgnoredVariants(project).forEach {
            assertTrue(it.name in ignoreVariants)
        }
    }
}

private class FakeAndroidVariantsExtractor : AndroidVariantsExtractor {
    override fun getVariants(project: Project): Set<BaseVariant> {
        return setOf(
            FakeVariant(DEBUG_FLAVOR1, FLAVOR1),
            FakeVariant(DEBUG_FLAVOR2, FLAVOR2),
            FakeVariant(RELEASE_FLAVOR1, FLAVOR1),
            FakeVariant(RELEASE_FLAVOR2, FLAVOR2)
        )
    }

    override fun getFlavors(project: Project): Set<ProductFlavor> {
        return listOf(FLAVOR1, FLAVOR2).map { FakeProductFlavor(it) }.toSet()
    }
}


