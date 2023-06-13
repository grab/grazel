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
import com.grab.grazel.util.ansiPurple
import com.grab.grazel.util.ansiYellow
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.process.ExecResult
import java.io.ByteArrayOutputStream
import java.io.OutputStream

// TODO(arun) Inject exec operations and avoid using Project
internal fun Project.bazelCommand(
    command: String,
    vararg args: String,
    ignoreExit: Boolean = false,
    outputStream: OutputStream? = null,
    errorOutputStream: OutputStream? = null,
): ExecResult {
    val commands: List<String> = mutableListOf("bazelisk", command).apply {
        addAll(args)
    }
    logger.quiet("${"Running".ansiGreen} ${commands.joinToString(separator = " ").ansiYellow}")
    return exec {
        commandLine(*commands.toTypedArray())
        standardOutput = outputStream ?: LogOutputStream(logger, LogLevel.QUIET)
        // Should be error but bazel wierdly outputs normal stuff to error
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