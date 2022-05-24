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

package com.grab.grazel.migrate.dependencies

import com.grab.grazel.buildProject
import com.grab.grazel.extension.MavenInstallExtension
import org.gradle.api.Project
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultArtifactsPinnerTest {
    private lateinit var rootProjectDir: File
    private lateinit var rootProject: Project
    private lateinit var artifactsPinner: DefaultArtifactsPinner

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        rootProjectDir = temporaryFolder.newFolder("project")
        rootProject = buildProject("root", projectDir = rootProjectDir)
    }


    @Test
    fun `assert when pinning is run for the first time without maven install @maven pin is chosen`() {
        val mavenInstallExtension = MavenInstallExtension(rootProject.objects)
        artifactsPinner = DefaultArtifactsPinner(
            rootProject,
            mavenInstallExtension
        )
        assertEquals(MavenTargets.Pinned.targetName, artifactsPinner.determinePinningTarget())
        assertTrue("Artifacts pinning is enabled by default") { artifactsPinner.isEnabled }
        assertNull(
            artifactsPinner.mavenInstallJson(),
            "Maven install json is not used for the first time"
        )
    }

    @Test
    fun `assert when pinning is run consecutively @unpinned_maven pin is chosen`() {
        val mavenInstallExtension = MavenInstallExtension(rootProject.objects)
        artifactsPinner = DefaultArtifactsPinner(
            rootProject,
            mavenInstallExtension
        )
        // Create dummy maven install file
        rootProject.file(mavenInstallExtension.artifactPinning.mavenInstallJson).writeText("")

        assertEquals(MavenTargets.Unpinned.targetName, artifactsPinner.determinePinningTarget())
        assertNotNull(artifactsPinner.mavenInstallJson(), "Maven install json target is added")
        assertTrue("Maven install json is converted to bazel label ") {
            artifactsPinner.mavenInstallJson()!!.startsWith("//")
        }
    }
}