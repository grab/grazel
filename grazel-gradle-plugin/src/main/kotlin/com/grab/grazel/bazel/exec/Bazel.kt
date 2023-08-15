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

package com.grab.grazel.bazel.exec

import com.grab.grazel.util.LogOutputStream
import com.grab.grazel.util.ansiGreen
import com.grab.grazel.util.ansiYellow
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logger
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import java.io.ByteArrayOutputStream
import java.io.OutputStream

@Deprecated(
    "Use exec operations variant instead of using Project instance",
    level = DeprecationLevel.WARNING
)
internal fun Project.bazelCommand(
    command: String,
    vararg args: String,
    ignoreExit: Boolean = false,
    outputStream: OutputStream? = null,
    errorOutputStream: OutputStream? = null,
): ExecResult = serviceOf<ExecOperations>().bazelCommand(
    logger,
    command,
    *args,
    ignoreExit = ignoreExit,
    outputStream = outputStream,
    errorOutputStream = errorOutputStream
)

internal fun ExecOperations.bazelCommand(
    logger: Logger,
    command: String,
    vararg args: String,
    ignoreExit: Boolean = false,
    outputStream: OutputStream? = null,
    errorOutputStream: OutputStream? = null,
): ExecResult {
    val commands = buildSet {
        add("bazelisk")
        add(command)
        addAll(args)
        add("--noshow_progress")
        add("--color=yes")
    }
    logger.quiet("${"Running".ansiGreen} ${commands.joinToString(separator = " ").ansiYellow}")
    return exec {
        commandLine(*commands.toTypedArray())
        standardOutput = outputStream ?: LogOutputStream(logger, LogLevel.QUIET)
        errorOutput = errorOutputStream ?: LogOutputStream(logger, LogLevel.QUIET)
        isIgnoreExitValue = ignoreExit
    }
}

// TODO(arun) Inject exec operations and avoid using Project
internal fun Project.executeCommand(
    vararg commands: String,
    ignoreExit: Boolean = true
): Pair<String, String> {
    val stdOut = ByteArrayOutputStream()
    val stdErr = ByteArrayOutputStream()
    project.exec {
        standardOutput = stdOut
        errorOutput = stdErr
        isIgnoreExitValue = ignoreExit
        commandLine(*commands)
    }
    return stdOut.toString().trim() to stdErr.toString().trim()
}