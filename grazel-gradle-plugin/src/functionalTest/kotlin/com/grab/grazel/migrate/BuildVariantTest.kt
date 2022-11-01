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
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.io.File

class BuildVariantTest : BaseGrazelPluginTest() {
    private val rootProject = File("src/test/projects/android-project")

    private val workspace = File(rootProject, "WORKSPACE")
    private val appBuildBazel = File(rootProject, "app/BUILD.bazel")
    private val androidFlavorBuildBazel = File(rootProject, "android-library-flavor/BUILD.bazel")

    private val bazelFiles = arrayOf(
        workspace,
        appBuildBazel,
        androidFlavorBuildBazel
    )

    @Before
    fun setupTest() {
        bazelFiles.forEach { it.delete() }
    }

    @Test
    fun migrateToBazelWithFlavorsWereUsed() {
        val task = arrayOf("migrateToBazel", "bazelBuildAll", "-P${MIGRATE_DATABINDING_FLAG}")

        runGradleBuild(task, rootProject) {
            val content = androidFlavorBuildBazel.readText()
            Assert.assertTrue(isMigrateToBazelSuccessful)
            verifyBazelFilesCreated()
            sourceShouldOnlyContainEnabledFlavorAndVariant(content)
            resourceShouldOnlyContainEnabledFlavorAndVariant(content)
            moduleDepsShouldOnlyContainEnabledFlavor(content)
        }
    }

    private fun moduleDepsShouldOnlyContainEnabledFlavor(buildFileContent: String) {
        Assert.assertTrue(
            "Contains Kotlin library flavor2",
            buildFileContent.contains(""""//kotlin-library-flavor2"""")
        )
        Assert.assertFalse(
            "Does not contain Kotlin library flavor1",
            buildFileContent.contains(""""//kotlin-library-flavor1"""")
        )
    }

    private fun sourceShouldOnlyContainEnabledFlavorAndVariant(buildFileContent: String) {
        Assert.assertTrue(
            "Contains Kotlin library flavor2 sources",
            buildFileContent.contains("""src/flavor2/java/com/grab/grazel/android/flavor""")
        )

        Assert.assertFalse(
            "Does not Kotlin library flavor1 sources",
            buildFileContent.contains("""src/flavor1/java/com/grab/grazel/android/flavor""")
        )

        Assert.assertTrue(
            "Contains Kotlin library main sources",
            buildFileContent.contains("""src/main/java/com/grab/grazel/android/flavor""")
        )
    }

    private fun resourceShouldOnlyContainEnabledFlavorAndVariant(buildFileContent: String) {
        Assert.assertTrue(
            "Contains flavor2 resources",
            buildFileContent.contains("""src/flavor2/res/""")
        )
        Assert.assertFalse(
            "Does not contain flavor1 resources",
            buildFileContent.contains("""src/flavor1/res/""")
        )
        Assert.assertTrue(
            "Contains main resources",
            buildFileContent.contains("""src/main/res/""")
        )
    }

    private fun verifyBazelFilesCreated() {
        Assert.assertTrue(workspace.exists())
        Assert.assertTrue(appBuildBazel.exists())
        Assert.assertTrue(androidFlavorBuildBazel.exists())
    }
}