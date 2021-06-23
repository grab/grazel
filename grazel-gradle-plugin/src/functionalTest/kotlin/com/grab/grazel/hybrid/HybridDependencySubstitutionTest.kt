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

package com.grab.grazel.hybrid

import com.grab.grazel.BaseGrazelPluginTest
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.File

class HybridDependencySubstitutionTest : BaseGrazelPluginTest() {

    private val fixtureRoot = File("src/test/projects/hybrid-dependency-substitution")

    private val appBuildBazel = File(fixtureRoot, "app/BUILD.bazel")
    private val androidLibraryBuildBazel = File(fixtureRoot, "android-library/BUILD.bazel")
    private val androidDatabindingBuildBazel = File(fixtureRoot, "android-databinding/BUILD.bazel")
    private val kotlinLibBuildBazel = File(fixtureRoot, "kotlin-lib/BUILD.bazel")
    private val workspaceFile = File(fixtureRoot, "WORKSPACE")

    private val buildTask = ":app:assembleDebug"

    private val bazelFiles = listOf(
        appBuildBazel,
        androidLibraryBuildBazel,
        androidDatabindingBuildBazel,
        kotlinLibBuildBazel,
        workspaceFile
    )

    private fun deleteBazelFiles() {
        bazelFiles.forEach { it.delete() }
    }

    @Before
    fun setupTest() {
        deleteBazelFiles()
        // Migrate whole project
        println("Generating bazel scripts")
        runGradleBuild(MIGRATE_TO_BAZEL, fixtureRoot)
    }

    @Test
    fun `assert kotlin modules are substituted correctly`() {
        println("Setting up hybrid build")
        appBuildBazel.delete()
        androidLibraryBuildBazel.delete()
        androidDatabindingBuildBazel.delete()
        assertHybridBuild()
    }

    @Test
    fun `assert kotlin android library modules are substituted correctly`() {
        println("Setting up hybrid build")
        appBuildBazel.delete()
        androidDatabindingBuildBazel.delete()
        assertHybridBuild()
    }

    @Test
    fun `assert when no projects are migrated dependency substitution is skipped`() {
        deleteBazelFiles()
        assertHybridBuild {
            val outcome = task(buildTask)?.outcome
            assertTrue(outcome == UP_TO_DATE || outcome == SUCCESS)
            assertTrue(!output.contains("Substituted"))
        }
    }

    @Test
    @Ignore("Databinding hybrid build is disabled in bazel-common")
    fun `assert databinding modules are substituted correctly`() {
        println("Setting up hybrid build")
        appBuildBazel.delete()
        assertHybridBuild()
    }

    private fun assertHybridBuild(
        assertions: BuildResult.() -> Unit = {
            val outcome = task(buildTask)?.outcome
            assertTrue(outcome == UP_TO_DATE || outcome == SUCCESS)
        }
    ) {
        println("Running hybrid build")
        runGradleBuild(arrayOf(buildTask, BAZEL_HYBRID_FLAG), fixtureRoot, assertions)
    }

    @After
    fun tearDown() {
        deleteBazelFiles()
    }
}