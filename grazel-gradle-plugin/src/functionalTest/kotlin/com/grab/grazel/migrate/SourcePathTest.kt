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
    @Ignore("Flaky when run in parallel; covered by dedicated source path unit coverage.")
    fun `assert common path is used in src attribute`() {
        val fixtureRoot = File("src/test/projects/kotlin-library")
        bazelClean(fixtureRoot)
        bazelBuild(fixtureRoot) {
            assertTrue(isMigrateToBazelSuccessful)
            val buildBazelFile = File(fixtureRoot, "lib/build.bazel").readText()
            assertTrue(
                buildBazelFile
                    .contains(""""src/main/kotlin/com/grab/grazel/kotlin/library/**/*.kt",""")
            )
        }
    }

    @Test
    fun `migrateToBazel uses src main assets for app and library assets`() {
        val task = arrayOf("migrateToBazel", "bazelBuildAll", "-P$MIGRATE_DATABINDING_FLAG")

        runGradleBuild(task, rootProject) {
            assertTrue(isMigrateToBazelSuccessful)
            verifyBazelFilesCreated()
            assetsAttributeShouldUseSrcMainAssets(appBuildBazel.readText())
            assetsAttributeShouldUseSrcMainAssets(androidLibraryBazel.readText())
        }
    }

    private fun assetsAttributeShouldUseSrcMainAssets(buildFileContent: String) {
        assertTrue(
            buildFileContent.contains(""""assets": "src/main/assets"""")
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
