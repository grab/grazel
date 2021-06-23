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

package com.grab.grazel.migrate

import com.android.build.gradle.LibraryExtension
import com.google.common.truth.Truth
import com.grab.grazel.GrazelExtension
import com.grab.grazel.GrazelExtension.Companion.GRAZEL_EXTENSION
import com.grab.grazel.bazel.starlark.asString
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.ANDROID_LIBRARY_PLUGIN
import com.grab.grazel.gradle.KOTLIN_ANDROID_PLUGIN
import com.grab.grazel.migrate.internal.ProjectBazelFileBuilder
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KotlinTargetsTest {
    private lateinit var projectBazelFileBuilder: ProjectBazelFileBuilder
    private lateinit var rootProject: Project
    private lateinit var rootProjectDir: File

    private lateinit var androidLibrary: Project
    private lateinit var projectDir: File

    @get:Rule
    val temporaryFolder = TemporaryFolder()


    private companion object {
        private const val PROJECT_NAME = "android-library"
    }

    @Before
    fun setup() {
        rootProjectDir = temporaryFolder.newFolder("project")
        rootProject = buildProject("root", projectDir = rootProjectDir)
        rootProject.extensions.add(GRAZEL_EXTENSION, GrazelExtension(rootProject))

        projectDir = File(rootProjectDir, PROJECT_NAME).apply {
            mkdirs()
        }
        configureAndroidLibraryProject()
        projectBazelFileBuilder = rootProject
            .createGrazelComponent()
            .projectBazelFileBuilderFactory()
            .create(androidLibrary)
    }

    private fun configureAndroidLibraryProject() {
        androidLibrary = buildProject(PROJECT_NAME, rootProject)
        androidLibrary.run {
            plugins.apply {
                apply(ANDROID_LIBRARY_PLUGIN)
                apply(KOTLIN_ANDROID_PLUGIN)
            }
            extensions.configure<LibraryExtension> {
                defaultConfig {
                    compileSdkVersion(29)
                }
                buildFeatures {
                    dataBinding = true
                }
            }
        }
        File(projectDir, "src/main/res/activity_main.xml").apply {
            parentFile.mkdirs()
            createNewFile()
            writeText(
                """
            <?xml version="1.0" encoding="utf-8"?>
            <layout xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                xmlns:tools="http://schemas.android.com/tools">
            </layout>
        """.trimIndent()
            )
        }
        File(projectDir, "src/main/AndroidManifest.xml").apply {
            createNewFile()
            writeText(
                """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                        package="com.example.androidlibrary">
                    </manifest>
                """.trimIndent()
            )
        }
    }

    @Test
    fun `assert for empty resources module with databinding enabled has kt_db_android_library generated`() {
        androidLibrary.doEvaluate()
        val generatedStatements = projectBazelFileBuilder.build().asString()
        Truth.assertThat(generatedStatements).apply {
            contains("kt_db_android_library")
            contains("""name = "android-library"""")
            contains(""""src/main/res/activity_main.xml",""")
            contains("""custom_package = "com.example.androidlibrary"""")
            contains("""manifest = "src/main/AndroidManifest.xml"""")
        }
    }
}