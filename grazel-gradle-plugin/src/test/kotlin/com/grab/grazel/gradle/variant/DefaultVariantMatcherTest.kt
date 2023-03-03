/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.gradle.ANDROID_LIBRARY_PLUGIN
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.KOTLIN_ANDROID_PLUGIN
import com.grab.grazel.gradle.KOTLIN_KAPT
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.assertErrorMessage
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class DefaultVariantMatcherTest {
    private lateinit var rootProject: Project
    private lateinit var appProject: Project
    private lateinit var libraryProject: Project
    private lateinit var variantMatcher: VariantMatcher

    private fun configure(
        app: AppExtension.() -> Unit = {},
        lib: LibraryExtension.() -> Unit = {}
    ) {
        rootProject = buildProject("root").also {
            it.addGrazelExtension()
        }
        appProject = buildProject("android", rootProject)
        libraryProject = buildProject("lib", rootProject)
        with(appProject) {
            with(plugins) {
                apply(ANDROID_APPLICATION_PLUGIN)
                apply(KOTLIN_ANDROID_PLUGIN)
                apply(KOTLIN_KAPT)
            }
            configure<AppExtension> {
                defaultConfig {
                    compileSdkVersion(32)
                }
                app(this)
            }
            dependencies {
                add("implementation", libraryProject)
            }
        }
        with(libraryProject) {
            with(plugins) {
                apply(ANDROID_LIBRARY_PLUGIN)
                apply(KOTLIN_ANDROID_PLUGIN)
                apply(KOTLIN_KAPT)
            }
            configure<LibraryExtension> {
                defaultConfig {
                    compileSdkVersion(32)
                }
                lib(this)
            }
        }

        libraryProject.doEvaluate()
        appProject.doEvaluate()
        val grazelComponent = rootProject.createGrazelComponent()
        variantMatcher = grazelComponent.variantMatcher().get()
    }

    private fun assertVariantNames(variants: Set<MatchedVariant>, vararg variantNames: String) {
        assertEquals(variantNames.size, variants.size)
        val matchedVariantName = variants.map { it.variantName }
        variantNames.forEach { expect ->
            assertTrue("$expect contains in matched variants") {
                expect in matchedVariantName
            }
        }
    }

    @Test
    fun `assert default case where only build types are present`() {
        configure()
        val matchedVariants = variantMatcher.matchedVariants(
            libraryProject,
            ConfigurationScope.BUILD
        )
        assertVariantNames(matchedVariants, "debug", "release")
    }

    @Test
    fun `assert app module with flavors and library module no flavors`() {
        configure(
            app = {
                flavorDimensions("type")
                productFlavors {
                    create("paid") {
                        dimension("type")
                    }
                    create("free") {
                        dimension("type")
                    }
                }
            },
        )
        val matchedVariants = variantMatcher.matchedVariants(
            libraryProject,
            ConfigurationScope.BUILD
        )
        assertVariantNames(
            matchedVariants,
            "paidDebug",
            "freeDebug",
            "paidRelease",
            "freeRelease"
        )
    }

    @Test
    fun `assert app module with flavors and library module with flavors`() {
        configure(
            app = {
                flavorDimensions("type")
                productFlavors {
                    create("paid") {
                        dimension("type")
                    }
                    create("free") {
                        dimension("type")
                    }
                }
            },
            lib = {
                flavorDimensions("type")
                productFlavors {
                    create("paid") {
                        dimension = ("type")
                    }
                    create("free") {
                        dimension = ("type")
                    }
                }
            }
        )
        val matchedVariants = variantMatcher.matchedVariants(
            libraryProject,
            ConfigurationScope.BUILD
        )
        assertVariantNames(
            matchedVariants,
            "paidDebug",
            "freeDebug",
            "paidRelease",
            "freeRelease"
        )
    }

    @Test
    fun `assert app module with 2 flavors and library module with 1 flavor`() {
        fun setup(addFallback: Boolean = false) {
            configure(
                app = {
                    flavorDimensions("type")
                    productFlavors {
                        create("paid") {
                            dimension("type")
                        }
                        create("free") {
                            dimension("type")
                            if (addFallback) {
                                setMatchingFallbacks("paid")
                            }
                        }
                    }
                },
                lib = {
                    flavorDimensions("type")
                    productFlavors {
                        create("paid") {
                            dimension = ("type")
                        }
                    }
                }
            )
        }
        setup()
        assertErrorMessage(
            "Fails due to no fallback",
            "Could not match [free] with :lib's flavors, ensure flavor `matchingFallbacks` are specified in :android"
        ) {
            variantMatcher.matchedVariants(
                libraryProject,
                ConfigurationScope.BUILD
            )
        }
        setup(addFallback = true)
        val results = variantMatcher.matchedVariants(
            libraryProject,
            ConfigurationScope.BUILD
        )
        assertVariantNames(
            results,
            "paidDebug",
            "freeDebug",
            "paidRelease",
            "freeRelease"
        )
    }

    @Test
    fun `assert app module with extra build type and library module with defaults`() {
        fun setup(addFallback: Boolean = false) {
            configure(
                app = {
                    flavorDimensions("type")
                    productFlavors {
                        create("paid") {
                            dimension("type")
                        }
                    }
                    buildTypes {
                        create("staging") {
                            if (addFallback) {
                                setMatchingFallbacks(listOf("debug", "release"))
                            }
                        }
                    }
                },
            )
        }
        setup()
        assertErrorMessage(
            "Fails due to no fallback",
            "Could not match app build type 'staging' with :lib's build type, ensure build type `matchingFallbacks` are specified in :android"
        ) {
            variantMatcher.matchedVariants(
                libraryProject,
                ConfigurationScope.BUILD
            )
        }
        setup(addFallback = true)
        val results = variantMatcher.matchedVariants(
            libraryProject,
            ConfigurationScope.BUILD
        )
        assertVariantNames(
            results,
            "paidDebug",
            "paidRelease",
            "paidStaging",
        )
    }

    @Test
    fun `assert app module with multiple dimensions and library module with single dimension`() {
        fun setup(addFallback: Boolean = false) {
            configure(
                app = {
                    flavorDimensions("type", "api")
                    productFlavors {
                        create("paid") {
                            dimension("type")
                        }
                        create("free") {
                            dimension("type")
                        }
                        create("minSdk") {
                            dimension("api")
                            if (addFallback) {
                                setMatchingFallbacks("free", "paid")
                            }
                        }
                    }
                },
                lib = {
                    flavorDimensions("type")
                    productFlavors {
                        create("paid") {
                            dimension = "type"
                        }
                    }
                }
            )
        }
        setup()
        assertErrorMessage(
            "Fails due to no fallback",
            "Could not match [free, minSdk] with :lib's flavors, ensure flavor `matchingFallbacks` are specified in :android"
        ) {
            variantMatcher.matchedVariants(
                libraryProject,
                ConfigurationScope.BUILD
            )
        }
        setup(addFallback = true)
        val results = variantMatcher.matchedVariants(
            libraryProject,
            ConfigurationScope.BUILD
        )
        assertVariantNames(
            results,
            "paidMinSdkDebug",
            "freeMinSdkDebug",
            "paidMinSdkRelease",
            "freeMinSdkRelease",
        )
    }

    @Test
    fun `assert app module with multiple dimensions and library module contains extra dimension`() {
        fun setup(addFallback: Boolean = false) {
            configure(
                app = {
                    flavorDimensions("type", "api")
                    productFlavors {
                        create("paid") {
                            dimension("type")
                        }
                        create("minSdk") {
                            dimension("api")
                            if (addFallback) {
                                setMatchingFallbacks("paid")
                            }
                        }
                    }
                },
                lib = {
                    flavorDimensions("type", "extra")
                    productFlavors {
                        create("paid") {
                            dimension = "type"
                        }
                        create("other") {
                            dimension = "extra"
                        }
                    }
                }
            )
        }
        setup(true)
        val results = variantMatcher.matchedVariants(
            libraryProject,
            ConfigurationScope.BUILD
        )
        assertVariantNames(
            results,
            "paidMinSdkDebug",
            "paidMinSdkRelease",
        )
    }

    @Test
    fun `assert app module with multiple dimensions and library with same dimensions`() {
        configure(
            app = {
                flavorDimensions("type", "api")
                productFlavors {
                    create("paid") {
                        dimension("type")
                    }
                    create("free") {
                        dimension("type")
                    }
                    create("minSdk21") {
                        dimension("api")
                    }
                    create("minSdk23") {
                        dimension("api")
                    }
                }
                buildTypes {
                    create("staging") {
                        setMatchingFallbacks("debug", "release")
                    }
                }
            },
            lib = {
                flavorDimensions("type", "api")
                productFlavors {
                    create("paid") {
                        dimension = "type"
                    }
                    create("free") {
                        dimension = "type"
                    }
                    create("minSdk21") {
                        dimension = "api"
                    }
                    create("minSdk23") {
                        dimension = "api"
                    }
                }
            }
        )
        val results = variantMatcher.matchedVariants(
            libraryProject,
            ConfigurationScope.BUILD
        )
        assertVariantNames(
            results,
            "paidMinSdk21Debug",
            "paidMinSdk23Debug",
            "paidMinSdk21Release",
            "paidMinSdk23Release",
            "paidMinSdk21Debug",
            "paidMinSdk23Release",
            "freeMinSdk21Debug",
            "freeMinSdk23Debug",
            "freeMinSdk21Release",
            "freeMinSdk23Release",
            "freeMinSdk21Debug",
            "freeMinSdk23Release",
        )
    }
}