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
import com.google.common.truth.Truth
import com.grab.grazel.GrazelExtension
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.gradle.KOTLIN_ANDROID_PLUGIN
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.the
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals

private const val ANDROID_BINARY_MODULE_NAME = "android-binary"

class AndroidInstrumentationBinaryDataExtractorTest : GrazelPluginTest() {
    private lateinit var androidBinary: Project
    private lateinit var androidBinaryDir: File

    private lateinit var androidInstrumentationBinaryDataExtractor: AndroidInstrumentationBinaryDataExtractor

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setup() {
        val rootProject = buildRootProject()
        val grazelComponent = rootProject.createGrazelComponent()
        androidInstrumentationBinaryDataExtractor = grazelComponent
            .androidInstrumentationBinaryDataExtractor()
            .get()
    }

    @Test
    fun `assert androidTest resources are parsed correctly`() {
        File(androidBinaryDir, "src/androidTest/resources/test1.json").apply {
            parentFile.mkdirs()
            createNewFile()
        }

        File(androidBinaryDir, "src/androidComponentUITest/resources/test2.json").apply {
            parentFile.mkdirs()
            createNewFile()
        }

        androidBinary.doEvaluate()
        val androidInstrumentationBinaryData = androidInstrumentationBinaryDataExtractor.extract(
            project = androidBinary,
            matchedVariant = debugAndroidTestVariant(androidBinary),
            sourceSetType = SourceSetType.JAVA_KOTLIN,
        )

        Truth.assertThat(androidInstrumentationBinaryData.resources).apply {
            contains("src/androidTest/resources/test1.json")
        }
        Truth.assertThat(androidInstrumentationBinaryData.resourceStripPrefix).apply {
            contains("$ANDROID_BINARY_MODULE_NAME/src/androidTest/resources")
        }
    }

    @Test
    fun `assert package name for android instrumentation binary does not contain test suffix`() {
        androidBinary.doEvaluate()
        val androidInstrumentationBinaryData = androidInstrumentationBinaryDataExtractor.extract(
            project = androidBinary,
            matchedVariant = debugAndroidTestVariant(androidBinary),
            sourceSetType = SourceSetType.JAVA_KOTLIN,
        )
        assertEquals(
            "com.example.androidlibrary",
            androidInstrumentationBinaryData.customPackage,
            "Package name does not have test suffix"
        )
    }

    private fun buildRootProject(): Project {
        val rootProjectDir = temporaryFolder.newFolder("project")
        val rootProject = buildProject("root", projectDir = rootProjectDir)
        rootProject.addGrazelExtension()
        rootProject.extensions.configure<GrazelExtension> {
            rules {
                test {
                    enableTestMigration = true
                }
            }
        }

        androidBinaryDir = File(rootProjectDir, ANDROID_BINARY_MODULE_NAME).apply {
            mkdirs()
        }
        androidBinary = buildProject(ANDROID_BINARY_MODULE_NAME, rootProject)
        androidBinary.run {
            plugins.apply {
                apply(ANDROID_APPLICATION_PLUGIN)
                apply(KOTLIN_ANDROID_PLUGIN)
            }
            extensions.configure<AppExtension> {
                defaultConfig {
                    applicationId = "com.example.androidlibrary"
                    compileSdkVersion(31)
                }
            }
        }
        File(androidBinaryDir, "src/main/AndroidManifest.xml").apply {
            parentFile.mkdirs()
            createNewFile()
            writeText(
                """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                        package="com.example.androidlibrary">
                    </manifest>
                """.trimIndent()
            )
        }

        return rootProject
    }

    private fun debugAndroidTestVariant(project: Project): MatchedVariant {
        val variant = project.the<AppExtension>().testVariants.first {
            it.name == "debugAndroidTest"
        }
        return MatchedVariant(
            variantName = variant.name,
            variant = variant,
            flavors = variant.productFlavors.map { it.name }.toSet(),
            buildType = variant.buildType.name
        )
    }
}
