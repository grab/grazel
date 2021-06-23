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

import com.android.build.gradle.LibraryExtension
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.buildProject
import com.grab.grazel.util.FLAVOR1
import com.grab.grazel.util.FLAVOR2
import com.grab.grazel.util.FakeAndroidBuildVariantDataSource
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue


class DefaultConfigurationDataSourceTest : GrazelPluginTest() {
    private lateinit var project: Project

    @Before
    fun setUp() {
        project = buildProject("android-lib-project")
            .also {
                it.plugins.apply {
                    apply(ANDROID_LIBRARY_PLUGIN)
                }
                it.extensions.configure<LibraryExtension> {
                    flavorDimensions("service")
                    productFlavors {
                        create(FLAVOR1) {
                            dimension = "service"
                        }
                        create(FLAVOR2) {
                            dimension = "service"
                        }
                    }
                }
            }
    }

    @Test
    fun `assert configurations filter out classpath and lint`() {
        val configurationDataSource = createNoFlavorFilterDataSource()
        val configurations = configurationDataSource.configurations(project).toList()
        assertTrue(configurations.isNotEmpty())
        configurations.forEach {
            assertFalse(it.name.contains("classpath"))
            assertFalse(it.name.contains("lint"))
        }
    }

    @Test
    fun `assert configurations filter out test`() {
        val configurationDataSource = createNoFlavorFilterDataSource()
        val configurations = configurationDataSource.configurations(project).toList()
        assertTrue(configurations.isNotEmpty())
        assertFalse(configurations.any { it.name.contains("test") })
    }

    @Test
    fun `when no variant filter applied, configurations should return all variants configurations`() {
        val configurationDataSource = createNoFlavorFilterDataSource()
        val configurations = configurationDataSource.configurations(project).toList()
        assertTrue(configurations.any { it.name.contains(FLAVOR1) })
        assertTrue(configurations.any { it.name.contains(FLAVOR2) })
    }

    private fun createNoFlavorFilterDataSource(): DefaultConfigurationDataSource {
        return DefaultConfigurationDataSource(FakeAndroidBuildVariantDataSource())
    }

    @Test
    fun `when variants filter applied, assert configurations ignore related variants and flavor`() {
        val fakeVariantDataSource = FakeAndroidBuildVariantDataSource(listOf(FLAVOR1))
        val configurationDataSource = DefaultConfigurationDataSource(fakeVariantDataSource)
        val configurations = configurationDataSource.configurations(project).toList()
        assertTrue(configurations.isNotEmpty())
        assertFalse(configurations.any { it.name.contains(FLAVOR1) })
        assertTrue(configurations.any { it.name.contains(FLAVOR2) })
    }

}



