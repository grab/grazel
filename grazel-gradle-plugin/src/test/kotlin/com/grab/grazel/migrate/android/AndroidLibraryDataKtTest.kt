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
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.bazel.rules.resValue
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.doEvaluate
import com.grab.grazel.util.truth
import org.codehaus.groovy.runtime.ArrayTypeUtils.dimension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.the
import org.junit.Test
import kotlin.test.assertEquals

class AndroidLibraryDataKtTest : GrazelPluginTest() {
    private lateinit var rootProject: Project
    private lateinit var appProject: Project

    private fun buildAndroidBinaryProject(
        compilerSdkVersion: Int = 30,
        block: AppExtension.() -> Unit = {}
    ): Project {
        rootProject = buildProject("root")
        rootProject.addGrazelExtension()
        appProject = buildProject("android-binary", rootProject)
        appProject.run {
            plugins.apply {
                apply(ANDROID_APPLICATION_PLUGIN)
            }
            extensions.configure<AppExtension> {
                namespace = "test"
                defaultConfig {
                    compileSdkVersion(compilerSdkVersion)
                }
                block(this)
            }

            doEvaluate()
        }
        return appProject
    }

    @Test
    fun `assert compileSdkVersion is parsed correctly for different API levels`() {
        fun Project.compileSdkVersion() = the<AppExtension>().compileSdkVersion
        assertEquals(
            30,
            parseCompileSdkVersion(buildAndroidBinaryProject(30).compileSdkVersion())
        )
        assertEquals(
            29,
            parseCompileSdkVersion(buildAndroidBinaryProject(29).compileSdkVersion())
        )
        assertEquals(
            28,
            parseCompileSdkVersion(buildAndroidBinaryProject(28).compileSdkVersion())
        )
        assertEquals(
            27,
            parseCompileSdkVersion(buildAndroidBinaryProject(27).compileSdkVersion())
        )
    }

    @Test
    fun `assert flavor specific res values are extracted`() {
        buildAndroidBinaryProject {
            buildTypes {
                getByName("debug") {
                    resValue("string", "type", "debug")
                }
            }
            flavorDimensions("dimension")
            productFlavors {
                create("free") {
                    resValue("string", "flavor", "free")
                    dimension("dimension")
                }
                create("paid") {
                    resValue("string", "flavor", "paid")
                    dimension("dimension")
                }
            }
        }
        val appExtension = appProject.the<AppExtension>()
        appExtension.applicationVariants
            .filter { it.buildType.name == "debug" }
            .map { variant ->
                MatchedVariant(
                    variantName = variant.name,
                    flavors = variant.productFlavors.map { it.name }.toSet(),
                    buildType = variant.buildType.name,
                    variant = variant
                )
            }.forEach { matchedVariant ->
                appExtension.extractResValue(matchedVariant).stringValues.truth {
                    hasSize(2)
                    containsEntry("type", "debug")
                    containsEntry("flavor", matchedVariant.flavors.first())
                }
            }
    }
}