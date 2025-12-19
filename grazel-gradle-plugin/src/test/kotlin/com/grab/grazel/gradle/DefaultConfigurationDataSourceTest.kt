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

import com.android.build.gradle.LibraryExtension
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.buildProject
import com.grab.grazel.fake.FLAVOR1
import com.grab.grazel.fake.FLAVOR2
import com.grab.grazel.fake.FakeAndroidVariantDataSource
import com.grab.grazel.gradle.variant.VariantType
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
    fun `configurations should return build configurations with AndroidBuild variant type`() {
        val fakeVariantDataSource = FakeAndroidVariantDataSource(listOf(FLAVOR1, FLAVOR2))
        val configurationDataSource = DefaultConfigurationDataSource(fakeVariantDataSource)
        val configurations = configurationDataSource
            .configurations(project, VariantType.AndroidBuild).toList()
        assertTrue(configurations.isNotEmpty())
        configurations.forEach { configuration ->
            assertTrue(configuration.isNotTest())
            assertFalse(configuration.isAndroidTest())
            assertFalse(configuration.isUnitTest())
        }
    }

    @Test
    fun `configurations should return test configurations with Test variant type`() {
        val fakeVariantDataSource = FakeAndroidVariantDataSource(listOf(FLAVOR1, FLAVOR2))
        val configurationDataSource = DefaultConfigurationDataSource(fakeVariantDataSource)
        val configurations = configurationDataSource
            .configurations(project, VariantType.Test).toList()
        assertTrue(configurations.isNotEmpty())
        assertTrue { configurations.any { it.isUnitTest() } }
        configurations.forEach { configuration ->
            assertTrue(
                configuration.isNotTest() || configuration.isUnitTest()
            )
            assertFalse(configuration.isAndroidTest())
        }
    }

    @Test
    fun `configurations should return android test configurations with AndroidTest variant type`() {
        val fakeVariantDataSource = FakeAndroidVariantDataSource(listOf(FLAVOR1, FLAVOR2))
        val configurationDataSource = DefaultConfigurationDataSource(fakeVariantDataSource)
        val configurations = configurationDataSource
            .configurations(project, VariantType.AndroidTest)
            .toList()
        assertTrue(configurations.isNotEmpty())
        assertTrue { configurations.any { it.isAndroidTest() } }
        configurations.forEach { configuration ->
            assertTrue(
                configuration.isNotTest() || configuration.isAndroidTest()
            )
            assertFalse(configuration.isUnitTest())
        }
    }

    @Test
    fun `configurations with no variant type should return all configurations`() {
        val fakeVariantDataSource = FakeAndroidVariantDataSource(listOf(FLAVOR1, FLAVOR2))
        val configurationDataSource = DefaultConfigurationDataSource(fakeVariantDataSource)

        // Test with no variant types - should return all configurations
        val allConfigurations = configurationDataSource
            .configurations(project).toList()
        assertTrue(allConfigurations.isNotEmpty())

        // Should include build, test, and android test configurations
        val buildConfigurations = configurationDataSource
            .configurations(project, VariantType.AndroidBuild).toList()
        val testConfigurations = configurationDataSource
            .configurations(project, VariantType.Test).toList()
        val androidTestConfigurations = configurationDataSource
            .configurations(project, VariantType.AndroidTest).toList()

        // All configurations should be a superset of each type
        assertTrue(allConfigurations.map { it.name }.containsAll(buildConfigurations.map { it.name }))
    }
}
