/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
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


