/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.grab.grazel.bazel.exec.bazelCommand
import com.grab.grazel.util.ansiRed
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logger
import org.gradle.internal.logging.progress.ProgressLogger
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream

/**
 * Custom [ByteArrayOutputStream] which can be used with [ExecOperations.bazelCommand] to parse and
 * detect if the maven dependencies are up to date and also log progress using [progressLogger]
 */
class BazelLogParsingOutputStream(
    private val logger: Logger,
    private val level: LogLevel,
    private val progressLogger: ProgressLogger,
    private val mavenRepo: String = "",
    private val logOutput: Boolean = true
) : ByteArrayOutputStream() {

    /**
     * Returns true if the maven dependencies are up to date determined by the presence of warning
     * from rules_jvm_external implementation. Must be called only after command is done.
     */
    var isOutOfDate: Boolean = false


    private val fileExts = listOf("aar", "jar", "pom", "sha1")

    override fun flush() {
        val message = toString()

        if (logOutput) {
            logger.log(level, message)
        }

        printProgress(message, progressLogger)

        if ("maven_install.json contains an invalid input signature and must be regenerated" in message && !isOutOfDate) {
            isOutOfDate = true
            logger.log(level, "WARNING: $mavenRepo repo is out of date.".ansiRed)
        }
        reset()
    }


    private fun extractArtifactName(line: String) = line.split("/")
        .dropLast(1) // File name
        .takeLast(4) // Maven path
        .joinToString(":")

    /**
     * From given raw bazel log message chunks in [message] try to extract a valid progress message
     * and print it.
     */
    internal fun printProgress(message: String, progressLogger: ProgressLogger) {
        // Last few lines will be the closest to the progress message.
        val lastFewLines = message.lines().dropLastWhile {
            val trimmed = it.trim()
            trimmed.isEmpty() || trimmed == "\u001B[0m"
        }.takeLast(10)

        lastFewLines
            .reversed()
            .asSequence()
            .drop(1) // Drop last line as stream could be incomplete.
            .firstOrNull { line ->
                "Download" in line // Check for downloading progress.
                    // Sometimes we get raw path instead of "Download" string, infer that using
                    // file name instead
                    || line.substringAfterLast("/").substringAfterLast(".") in fileExts
            }?.let { line ->
                progressLogger.progress("Downloading " + extractArtifactName(line))
            }
    }
}