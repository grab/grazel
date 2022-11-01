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

package com.grab.grazel.migrate.android

import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.internal.dsl.BuildType
import com.android.build.gradle.internal.dsl.ProductFlavor
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.gradle.ANDROID_LIBRARY_PLUGIN
import com.grab.grazel.gradle.AndroidVariantDataSource
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.KOTLIN_ANDROID_PLUGIN
import com.grab.grazel.util.doEvaluate
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.whenever
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.the
import org.junit.Assert

class VariantsMergerRobo {
    private val app: Project
    private val dep: Project
    private val expectedVariants = mutableSetOf<MergedVariant>()
    private lateinit var appVariants: Set<BaseVariant>
    private lateinit var depVariants: Set<BaseVariant>
    private val androidVariantDataSource: AndroidVariantDataSource = mock()
    private val root = buildProject("root")
    private lateinit var scope: ConfigurationScope

    init {
        app = with(buildProject("app", root)) {
            plugins.apply {
                apply(ANDROID_APPLICATION_PLUGIN)
            }
            extensions.configure<AppExtension> {
                defaultConfig {
                    compileSdkVersion(29)
                    buildToolsVersion("29.0.3")
                }
            }

            this
        }

        dep = with(buildProject("dep", root)) {
            plugins.apply {
                apply(ANDROID_LIBRARY_PLUGIN)
                apply(KOTLIN_ANDROID_PLUGIN)
            }
            extensions.configure<LibraryExtension> {
                defaultConfig {
                    compileSdkVersion(30)
                }
            }
            this
        }
    }

    fun scope(scope: ConfigurationScope): VariantsMergerRobo {
        this.scope = scope

        return this
    }

    fun appFlavor(flavor: String, dimension: String, vararg fallbacks: String): VariantsMergerRobo {
        with(app) {
            with(the<AppExtension>()) {
                if (!flavorDimensionList.contains(dimension)) {
                    flavorDimensionList.add(dimension)
                }
                productFlavors.create(flavor, object : Action<ProductFlavor> {
                    override fun execute(productFlavor: ProductFlavor) {
                        productFlavor.dimension = dimension
                        productFlavor.matchingFallbacks.addAll(fallbacks)
                    }
                })
            }
        }

        return this
    }

    fun appBuildType(buildType: String, vararg fallbacks: String): VariantsMergerRobo {
        with(app) {
            with(the<AppExtension>()) {
                buildTypes(object :
                    Action<NamedDomainObjectContainer<BuildType>> {
                    override fun execute(p0: NamedDomainObjectContainer<BuildType>) {
                        p0.create(buildType, object : Action<com.android.build.api.dsl.BuildType> {
                            override fun execute(buildType: com.android.build.api.dsl.BuildType) {
                                buildType.matchingFallbacks.addAll(fallbacks)
                            }
                        })
                    }
                })
            }
        }
        return this
    }

    fun evaluate(): VariantsMergerRobo {
        app.doEvaluate()
        dep.doEvaluate()

        appVariants = app.the<AppExtension>().applicationVariants.toSet()
        depVariants = dep.the<LibraryExtension>().libraryVariants.toSet()
        whenever(androidVariantDataSource.getMigratableVariants(app, scope)).thenReturn(
            appVariants
        )
        whenever(androidVariantDataSource.getMigratableVariants(dep, scope)).thenReturn(
            depVariants
        )
        return this
    }

    fun expectedVariant(
        flavor: String,
        buildType: String,
        moduleVariantName: String
    ): VariantsMergerRobo {
        expectedVariants.add(
            MergedVariant(
                flavor,
                buildType,
                depVariants.first { it.name == moduleVariantName })
        )
        return this
    }

    fun verifyDepMergedVariants() {
        val mergedVariants = VariantsMerger(
            androidVariantDataSource,
            root
        ).merge(dep, scope)

        Assert.assertEquals(expectedVariants, mergedVariants)
    }

    fun <T : Throwable> verifyDepMergedVariantsThrow(error: Class<T>) {
        Assert.assertThrows(error) {
            VariantsMerger(
                androidVariantDataSource,
                root
            ).merge(dep, scope)
        }
    }

    fun depFlavor(flavor: String, dimension: String, vararg fallbacks: String): VariantsMergerRobo {
        with(dep) {
            with(the<LibraryExtension>()) {
                if (!flavorDimensionList.contains(dimension)) {
                    flavorDimensionList.add(dimension)
                }
                productFlavors.create(flavor, object : Action<ProductFlavor> {
                    override fun execute(productFlavor: ProductFlavor) {
                        productFlavor.dimension = dimension
                        productFlavor.matchingFallbacks.addAll(fallbacks)
                    }
                })
            }
        }

        return this
    }

    fun anotherAppModule(): VariantsMergerRobo {
        with(buildProject("app2", root)) {
            plugins.apply {
                apply(ANDROID_APPLICATION_PLUGIN)
            }
            extensions.configure<AppExtension> {
                defaultConfig {
                    compileSdkVersion(29)
                    buildToolsVersion("29.0.3")
                }
            }
        }

        return this
    }
}