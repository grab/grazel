/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
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
            contains("https://bazel-common-android")
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