/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate.android

import com.android.build.gradle.AppExtension
import com.grab.grazel.GrazelExtension
import com.grab.grazel.GrazelExtension.Companion.GRAZEL_EXTENSION
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.util.doEvaluate
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.the
import org.junit.Test
import kotlin.test.assertEquals

class AndroidLibraryDataKtTest : GrazelPluginTest() {

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

    private fun buildAndroidBinaryProject(compilerSdkVersion: Int): Project {
        val rootProject = buildProject("root")
        rootProject.extensions.add(GRAZEL_EXTENSION, GrazelExtension(rootProject))
        val androidBinary = buildProject("android-binary", rootProject)
        androidBinary.run {
            plugins.apply {
                apply(ANDROID_APPLICATION_PLUGIN)
            }
            extensions.configure<AppExtension> {
                defaultConfig {
                    compileSdkVersion(compilerSdkVersion)
                }
            }
            doEvaluate()
        }
        return androidBinary
    }
}