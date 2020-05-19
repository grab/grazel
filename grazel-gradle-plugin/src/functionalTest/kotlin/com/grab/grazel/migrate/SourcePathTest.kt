/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate

import com.grab.grazel.BaseGrazelPluginTest
import com.grab.grazel.util.MIGRATE_DATABINDING_FLAG
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.File

class SourcePathTest : BaseGrazelPluginTest() {

    private val rootProject = File("src/test/projects/android-project")
    private val workspace = File(rootProject, "WORKSPACE")
    private val appBuildBazel = File(rootProject, "app/BUILD.bazel")
    private val androidLibraryBazel = File(rootProject, "android-library/BUILD.bazel")

    private val bazelFiles = arrayOf(
        workspace,
        appBuildBazel,
        androidLibraryBazel
    )

    @Before
    fun setupTest() {
        bazelFiles.forEach { it.delete() }
    }

    @Test
    @Ignore("Is flaky when run parallely but passes when run individually. Currently covered by FileUtilsKtTest")
    fun `assert common path is used in src attribute`() {
        val fixtureRoot = File("src/test/projects/kotlin-library")
        bazelClean(fixtureRoot)
        bazelBuild(fixtureRoot) {
            assertTrue(isMigrateToBazelSuccessful)
            val buildBazelFile = File(fixtureRoot, "/lib/build.bazel").readText()
            assertTrue(
                buildBazelFile
                    .contains(""""src/main/kotlin/com/grab/grazel/kotlin/library/**/*.kt",""")
            )
        }
    }

    @Test
    fun migrateToBazelWithAssert() {
        val task = arrayOf("migrateToBazel", "bazelBuildAll", "-P$MIGRATE_DATABINDING_FLAG")

        runGradleBuild(task, rootProject) {
            assertTrue(isMigrateToBazelSuccessful)
            verifyBazelFilesCreated()
            assetsAppAssetsShouldBeSet(appBuildBazel.readText())
            assetsLibsValuesShouldBeSet(androidLibraryBazel.readText())
        }
    }

    private fun assetsAppAssetsShouldBeSet(buildFileContent: String) {
        assertTrue(
            buildFileContent.contains("src/main/assets/assert-file.png")
        )
        assertTrue(
            buildFileContent.contains("""src/main/assets""")
        )
    }


    private fun assetsLibsValuesShouldBeSet(buildFileContent: String) {
        assertTrue(
            buildFileContent.contains("src/main/assets/Android_new_logo_2019.svg")
        )
        assertTrue(
            buildFileContent.contains("""src/main/assets""")
        )
    }


    private fun verifyBazelFilesCreated() {
        assertTrue(workspace.exists())
        assertTrue(appBuildBazel.exists())
        assertTrue(androidLibraryBazel.exists())
    }
}