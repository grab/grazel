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

import com.grab.grazel.gradle.isMigrated
import com.grab.grazel.util.BAZEL_ENABLED
import com.grab.grazel.util.KT_INTERMEDIATE_TARGET_SUFFIX
import com.grab.grazel.util.LogOutputStream
import com.grab.grazel.util.booleanProperty
import com.grab.grazel.util.localProperties
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel.QUIET
import java.io.ByteArrayOutputStream
import java.io.OutputStream

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

/**
 * Given a combined sequence of `android_library` and `kt_android_library` targets will return unique targets i.e
 * `android_library` alone.
 */
internal fun findUniqueAarTargets(aarTargets: Sequence<String>): List<String> {
    // Filter out _base target added by kt_android_library
    val allAarTargets = aarTargets.map {
        if (it.endsWith(KT_INTERMEDIATE_TARGET_SUFFIX)) {
            it.split(KT_INTERMEDIATE_TARGET_SUFFIX).first()
        } else it
    }
    // The remaining unique entries are aar targets
    return mutableMapOf<String, String>()
        .apply {
            allAarTargets.forEach { target ->
                if (containsKey(target))
                    remove(target)
                else {
                    put(target, target)
                }
            }
        }.keys
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { "$it.aar" }
        .toList()
}


internal fun collectDatabindingAarTargets(aarTargets: Sequence<String>) = aarTargets
    .filter { it.isNotEmpty() }
    .map { "$it.aar" }
    .toList()

private fun Project.buildAarTargets() {
    // Query android library targets
    val (bazelAarOut, _) = executeCommand("bazelisk", "query", "kind(android_library, //...:*)")

    // Query databinding aars
    val (databindingAar, _) = executeCommand("bazelisk", "query", "kind(databinding_aar, //...:*)")

    val aarTargets = findUniqueAarTargets(bazelAarOut.lineSequence()) +
            collectDatabindingAarTargets(databindingAar.lineSequence())
    logger.quiet("Found aar targets : $aarTargets")

    bazelCommand("build", *aarTargets.distinct().toTypedArray(), ignoreExit = true)
}

internal fun Project.bazelCommand(
    command: String,
    vararg targets: String,
    ignoreExit: Boolean = false,
    outputstream: OutputStream? = null
) {
    val commands: List<String> = mutableListOf("bazelisk", command).apply {
        addAll(targets)
    }
    logger.quiet("Running ${commands.joinToString(separator = " ")}")
    exec {
        commandLine(*commands.toTypedArray())
        standardOutput = outputstream ?: LogOutputStream(logger, QUIET)
        // Should be error but bazel wierdly outputs normal stuff to error
        errorOutput = LogOutputStream(logger, QUIET)
        isIgnoreExitValue = ignoreExit
    }
}

internal fun Project.dozerCommand(
    command: String,
    vararg targets: String,
    ignoreExit: Boolean = false
) {
    val commands: List<String> = mutableListOf("buildozer", command, targets.joinToString(","))
    exec {
        commandLine(*commands.toTypedArray())
        standardOutput = LogOutputStream(logger, QUIET)
        // Should be error but bazel wierdly outputs normal stuff to error
        errorOutput = LogOutputStream(logger, QUIET)
        isIgnoreExitValue = ignoreExit
    }
}

/**
 * Performs Bazel build on all targets and aar targets before Gradle build starts during configuration phase.
 *
 * `aar` targets are determined by `android_library` rule.
 */
internal fun Project.doHybridBuild() {
    val isHybridBuild = booleanProperty(BAZEL_ENABLED, localProperties())
    if (!isHybridBuild) {
        return
    }
    // Validate if we can run hybrid build
    if (isMigrated) {
        logger.quiet("Running hybrid build")
        bazelCommand("build", "//...")
        buildAarTargets()
        bazelCommand("shutdown")

        registerDependencySubstitutionRules()
    } else {
        logger.quiet("Skipping hybrid build due to lack of Bazel files")
    }
}