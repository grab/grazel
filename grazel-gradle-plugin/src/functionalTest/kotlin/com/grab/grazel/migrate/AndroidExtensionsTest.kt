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

import com.google.common.truth.Truth
import com.grab.grazel.BaseGrazelPluginTest
import com.grab.grazel.util.MIGRATE_DATABINDING_FLAG
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.io.File

class AndroidExtensionsTest : BaseGrazelPluginTest() {
    private val rootProject = File("src/test/projects/android-project")

    private val workspace = File(rootProject, "WORKSPACE")
    private val rootBuildFile = File(rootProject, "BUILD.bazel")
    private val appBuildBazel = File(rootProject, "app/BUILD.bazel")
    private val androidLibraryBuildBazel = File(rootProject, "android-library/BUILD.bazel")
    private val kotlinLibrary1BuildBazel = File(rootProject, "kotlin-library1/BUILD.bazel")

    private val parcelizeTarget = "//:parcelize"

    private val bazelFiles = arrayOf(
        workspace,
        appBuildBazel,
        androidLibraryBuildBazel,
        kotlinLibrary1BuildBazel
    )

    @Before
    fun setupTest() {
        bazelFiles.forEach { it.delete() }
    }

    @Test
    fun migrateToBazelWithAndroidExtensionsIsUsed() {
        val task = arrayOf("migrateToBazel", "-P$MIGRATE_DATABINDING_FLAG")
        runGradleBuild(task, rootProject) {
            Assert.assertTrue(isMigrateToBazelSuccessful)
            verifyBazelFilesCreated()
            verifyWorkspaceFile()
            verifyRootBuildFile()
            verifyLibBuildFile()
            verifyAppBuildFile()
        }
    }

    private fun verifyAppBuildFile() {
        val content = appBuildBazel.readText()
        Truth.assertThat(content).contains("\"$parcelizeTarget\",")
    }

    private fun verifyLibBuildFile() {
        val content = androidLibraryBuildBazel.readText()
        Truth.assertThat(content).contains("\"$parcelizeTarget\",")
    }

    private fun verifyRootBuildFile() {
        val content = rootBuildFile.readText()
        Truth.assertThat(content).apply {
            content.contains("""load("@grab_bazel_common//tools/parcelize:parcelize.bzl", "parcelize_rules")""")
            content.contains("parcelize_rules()")
        }
    }

    /**
     * //TODO move bazel common worspace tests to different class
     */
    private fun verifyWorkspaceFile() {
        val workspaceContent = workspace.readText()
        Truth.assertThat(workspaceContent).apply {
            contains("https://github.com/grab/grab-bazel-common.git")
            contains("grab_bazel_common")
        }
    }

    private fun verifyBazelFilesCreated() {
        Assert.assertTrue(kotlinLibrary1BuildBazel.exists())
        Assert.assertTrue(workspace.exists())
        Assert.assertTrue(appBuildBazel.exists())
        Assert.assertTrue(androidLibraryBuildBazel.exists())
        Assert.assertTrue(rootBuildFile.exists())
    }
}