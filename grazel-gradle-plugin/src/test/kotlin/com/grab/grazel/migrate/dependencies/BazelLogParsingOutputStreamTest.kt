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
import com.grab.grazel.fake.FakeLogger
import com.grab.grazel.fake.FakeProgressLogger
import com.grab.grazel.fake.FakeProgressLoggerFactory
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.internal.logging.progress.ProgressLoggerFactory
import org.junit.Before
import org.junit.Test
import java.io.PrintStream
import kotlin.test.assertTrue

class BazelLogParsingOutputStreamTest {

    private lateinit var rootProject: Project
    private lateinit var bazelLogParsingOutputStream: BazelLogParsingOutputStream
    private lateinit var logger: FakeLogger
    private lateinit var progressLoggerFactory: ProgressLoggerFactory
    private lateinit var progressLogger: FakeProgressLogger

    @Before
    fun setup() {
        rootProject = buildProject("root")
        progressLoggerFactory = FakeProgressLoggerFactory()
        progressLogger = progressLoggerFactory.newOperation("test") as FakeProgressLogger
        logger = FakeLogger()
        bazelLogParsingOutputStream = BazelLogParsingOutputStream(
            logger = logger,
            level = LogLevel.QUIET,
            progressLogger = progressLogger,
        )
    }

    @Test
    fun `assert logs are streamed to logger`() {
        PrintStream(bazelLogParsingOutputStream, false).apply {
            println("empty")
            flush()
        }
        assertTrue("Logs are reported") {
            logger.logs.any { it.message?.contains("empty") == true }
        }
    }

    @Test
    fun `assert when invalid signature error is preset for rules_jvm_external output, out of date is set`() {
        val message = "maven_install.json contains an invalid input signature and " +
            "must be regenerated"
        PrintStream(bazelLogParsingOutputStream, false).apply {
            println(message)
            println("")
            flush()
        }
        assertTrue("Stream contains Invalid Signature error") {
            logger.logs.any { it.message?.contains(message) == true }
        }
        assertTrue("Out of date is set") {
            bazelLogParsingOutputStream.isOutOfDate
        }
    }

    @Test
    fun `assert download progress are reported by parsing download log`() {
        PrintStream(bazelLogParsingOutputStream, false).apply {
            println(
                """org.jetbrains.kotlinx:atomicfu:0.17.3

org.json:json:20210307

Downloading https://dl.google.com/dl/android/maven2/androidx/databinding/databinding-common/7.2.2/databinding-common-7.2.2.pom
Downloading https://dl.google.com/dl/android/maven2/androidx/activity/activity-ktx/1.7.2/activity-ktx-1.7.2.pom
Downloading https://dl.google.com/dl/android/maven2/androidx/arch/core/core-common/2.2.0/core-common-2.2.0.pom

"""
            )
            flush()
        }
        assertTrue("Download progress is reported from penultimate line") {
            progressLogger.progressMessages.any { it.contains("Downloading androidx:activity:activity-ktx:1.7.2") }
        }
    }

    @Test
    fun `assert download progress are reported by parsing fetch requests`() {
        PrintStream(bazelLogParsingOutputStream, false).apply {
            println(
                """8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3 /private/var/tmp/_bazel/db23b8d128409b6396481457deacb461/external/test_maven/v1/https/repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar
66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9 /private/var/tmp/_bazel/db23b8d128409b6396481457deacb461/external/test_maven/v1/https/repo.maven.apache.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar"""
            )
            flush()
        }
        assertTrue("Download progress is reported from penultimate line") {
            progressLogger.progressMessages.any { it.contains("Downloading maven2:junit:junit:4.13.2") }
        }
    }
}