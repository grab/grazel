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

package com.grab.grazel.gradle

import com.grab.grazel.GrazelExtension
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.variant.AndroidVariantDataSource
import com.grab.grazel.gradle.variant.TEST_FLAVOR_PAID
import com.grab.grazel.gradle.variant.setupAndroidVariantProject
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import org.gradle.api.Project
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertTrue

class DefaultAndroidVariantDataSourceTest : GrazelPluginTest() {
    private lateinit var rootProject: Project
    private lateinit var androidProject: Project
    private lateinit var androidVariantDataSource: AndroidVariantDataSource

    fun setup(configure: GrazelExtension.() -> Unit = {}) {
        rootProject = buildProject("root")
        rootProject.addGrazelExtension(configure)
        androidProject = buildProject("android", rootProject)
        setupAndroidVariantProject(androidProject)
        val grazelComponent = rootProject.createGrazelComponent()
        androidVariantDataSource = grazelComponent.androidVariantDataSource().get()
        androidProject.doEvaluate()
    }

    @Test
    fun `when config to ignore variant, assert the related flavors also be ignored`() {
        setup {
            android {
                variantFilter {
                    if (name.startsWith(TEST_FLAVOR_PAID)) {
                        setIgnore(true)
                    }
                }
            }
        }
        val ignoreFlavors = androidVariantDataSource.getIgnoredFlavors(androidProject)
        assertEquals(1, ignoreFlavors.size)
        assertEquals(TEST_FLAVOR_PAID, ignoreFlavors.first().name)
    }

    @Test
    fun `when no filter applied, assert ignore flavor return empty list`() {
        setup()
        val ignoreFlavors = androidVariantDataSource.getIgnoredFlavors(androidProject)
        assertEquals(0, ignoreFlavors.size)
    }

    @Test
    fun `when no variants filter applied, assert ignored variants should return empty list`() {
        setup()
        val ignoreVariants = androidVariantDataSource.getIgnoredVariants(androidProject)
        assertEquals(0, ignoreVariants.size)
    }


    @Test
    fun `when variants filter applied, assert ignored variants should be returned`() {
        setup {
            android {
                variantFilter {
                    if (name.startsWith(TEST_FLAVOR_PAID)) {
                        setIgnore(true)
                    }
                }
            }
        }
        val ignoreVariants = androidVariantDataSource.getIgnoredVariants(androidProject)
        assertTrue("Ignored variants are returned") {
            ignoreVariants.all { it.name.startsWith(TEST_FLAVOR_PAID) }
        }
    }
}

