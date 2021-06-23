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