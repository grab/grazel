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

package com.grab.grazel

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

abstract class BaseGrazelPluginTest {

    protected lateinit var gradleRunner: GradleRunner

    @get:Rule
    val testProjectDir = TemporaryFolder()

    @Before
    fun setup() {
        gradleRunner = GradleRunner
            .create()
            .withPluginClasspath()
    }

    protected val MIGRATE_TO_BAZEL = "migrateToBazel"
    protected val BAZEL_CLEAN = "bazelClean"
    protected val BAZEL_BUILD = "bazelBuildAll"
    protected val BAZEL_HYBRID_FLAG = "-PbazelEnabled=true"

    protected fun runGradleBuild(
        arguments: String,
        fixtureRoot: File,
        assertions: BuildResult.() -> Unit = {}
    ): BuildResult = runGradleBuild(arrayOf(arguments), fixtureRoot, assertions)

    protected fun runGradleBuild(
        arguments: Array<String>,
        fixtureRoot: File,
        assertions: BuildResult.() -> Unit = {}
    ): BuildResult = gradleRunner
        .withProjectDir(fixtureRoot)
        .withArguments(arguments.toList())
        .forwardOutput()
        .build()
        .also(assertions)

    protected fun runTaskAndClean(taskName: String, fixtureRoot: File, assertions: BuildResult.() -> Unit = {}) {
        runGradleBuild(taskName, fixtureRoot, assertions)
        bazelClean(fixtureRoot)
    }

    protected fun bazelClean(fixtureRoot: File) {
        runGradleBuild(BAZEL_CLEAN, fixtureRoot)
    }

    protected fun migrateToBazel(fixtureRoot: File, assertions: BuildResult.() -> Unit = {}) {
        runTaskAndClean(MIGRATE_TO_BAZEL, fixtureRoot, assertions)
    }

    protected fun bazelBuild(fixtureRoot: File, assertions: BuildResult.() -> Unit = {}) {
        runTaskAndClean(BAZEL_BUILD, fixtureRoot, assertions)
    }

    protected val BuildResult.isMigrateToBazelSuccessful: Boolean
        get() = task(":$MIGRATE_TO_BAZEL")?.outcome == SUCCESS

    protected val BuildResult.isBazelCleanSuccessful: Boolean
        get() = task(":$BAZEL_CLEAN")?.outcome == SUCCESS

    protected val BuildResult.isBazelBuildSuccessful: Boolean
        get() = task(":$BAZEL_BUILD")?.outcome == SUCCESS
}