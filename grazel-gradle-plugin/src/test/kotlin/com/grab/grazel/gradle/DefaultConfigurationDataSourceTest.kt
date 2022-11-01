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
import com.grab.grazel.fake.FakeConfiguration
import com.grab.grazel.fake.FakeProject
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    fun `assert configurations filter out dynamic-feature configuration`() {
        val dynamicFeatureConfigurationName = "debugReverseMetadataValues"
        val configurationDataSource = createNoFlavorFilterDataSource()
        val project = FakeProject("project").also {
            it.shadowConfigurations = listOf(
                FakeConfiguration("implementation"),
                FakeConfiguration(dynamicFeatureConfigurationName),
            )
        }

        val configurations = configurationDataSource.configurations(project).toList()
        assertTrue(configurations.isNotEmpty())
        configurations.forEach {
            assertNotEquals(dynamicFeatureConfigurationName, it.name)
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
        return DefaultConfigurationDataSource(FakeAndroidVariantDataSource())
    }

    @Test
    fun `when variants filter applied, assert configurations ignore related variants and flavor`() {
        val fakeVariantDataSource = FakeAndroidVariantDataSource(listOf(FLAVOR1))
        val configurationDataSource = DefaultConfigurationDataSource(fakeVariantDataSource)
        val configurations = configurationDataSource.configurations(project).toList()
        assertTrue(configurations.isNotEmpty())
        assertFalse(configurations.any { it.name.contains(FLAVOR1) })
        assertTrue(configurations.any { it.name.contains(FLAVOR2) })
    }

    @Test
    fun `configurations should not return test or android tests configuration with build scope`() {
        val fakeVariantDataSource = FakeAndroidVariantDataSource(listOf(FLAVOR1, FLAVOR2))
        val configurationDataSource = DefaultConfigurationDataSource(fakeVariantDataSource)
        val configurations = configurationDataSource
            .configurations(project, ConfigurationScope.BUILD).toList()
        assertTrue(configurations.isNotEmpty())
        configurations.forEach { configuration ->
            assertTrue(configuration.isNotTest())
            assertFalse(configuration.isAndroidTest())
            assertFalse(configuration.isUnitTest())
        }
    }

    @Test
    fun `configurations should return test and build configurations with test scope`() {
        val fakeVariantDataSource = FakeAndroidVariantDataSource(listOf(FLAVOR1, FLAVOR2))
        val configurationDataSource = DefaultConfigurationDataSource(fakeVariantDataSource)
        val configurations = configurationDataSource
            .configurations(project, ConfigurationScope.TEST).toList()
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
    fun `configurations should return android test and build configurations with android test scope`() {
        val fakeVariantDataSource = FakeAndroidVariantDataSource(listOf(FLAVOR1, FLAVOR2))
        val configurationDataSource = DefaultConfigurationDataSource(fakeVariantDataSource)
        val configurations = configurationDataSource
            .configurations(project, ConfigurationScope.ANDROID_TEST)
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
}



