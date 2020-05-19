/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
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



