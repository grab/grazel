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

import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.extension.MavenInstallExtension
import com.grab.grazel.hybrid.bazelCommand
import com.grab.grazel.migrate.dependencies.MavenPinningError.InvalidSignature
import com.grab.grazel.migrate.dependencies.MavenPinningError.JsonCorrupted
import com.grab.grazel.util.ansiYellow
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contract to manage Rules Jvm External's
 * [artifact pinning](https://github.com/bazelbuild/rules_jvm_external#pinning-artifacts-and-integration-with-bazels-downloader)
 */
internal interface ArtifactsPinner {

    val isEnabled: Boolean

    /**
     * @return The mavenInstall json target label for Bazel, null if it could not be added
     */
    fun mavenInstallJson(): String?

    /**
     * Run rules jvm external's artifact pinning
     */
    fun pin(workspaceFile: File)
}

internal sealed class MavenPinningError(val message: String) {
    /**
     * Error due to corrupt maven_install.json
     */
    data class JsonCorrupted(
        val msg: String = "Json corrupted".ansiYellow
    ) : MavenPinningError(msg)

    /**
     * Error due to mismatching signature in  maven_install.json.
     */
    data class InvalidSignature(
        val msg: String = "Signature mismatch detected.".ansiYellow
    ) : MavenPinningError(msg)

    companion object {
        fun parseFrom(output: String): MavenPinningError? {
            return when {
                output.contains("Error in fail: [A-z\\-]*.\\.json contains an invalid signature and may be corrupted".toRegex()) -> InvalidSignature()
                output.contains("Is this file valid JSON?") -> JsonCorrupted()
                else -> null
            }
        }
    }
}

/**
 * [ByteArrayOutputStream] implementation to parse bazel command output information and extract relevant
 * info out of it. Currently, it is used to detect build failure reason.
 */
internal class MavenPinningOutputStream(
    private val logger: Logger,
) : ByteArrayOutputStream() {
    /**
     * @return [MavenPinningError] parsed from Bazel's build output, empty otherwise
     */
    internal val errors: List<MavenPinningError> get() = _errors.toList()

    private val _errors = mutableSetOf<MavenPinningError>()

    override fun flush() {
        val output = toString()
        val error = MavenPinningError.parseFrom(output)
        if (error != null) {
            _errors.add(error)
        }
        if (_errors.isEmpty()) {
            // Don't log known errors since errors should be handled gracefully in Pinner
            logger.log(LogLevel.QUIET, output)
        }
        reset()
    }
}

internal enum class MavenTargets(val targetName: String) {
    Unpinned("@unpinned_maven//:pin"),
    Pinned("@maven//:pin")
}


@Singleton
internal class DefaultArtifactsPinner
@Inject
constructor(
    @param:RootProject private val rootProject: Project,
    private val mavenInstallExtension: MavenInstallExtension
) : ArtifactsPinner {

    private val artifactPinning get() = mavenInstallExtension.artifactPinning

    override val isEnabled: Boolean get() = mavenInstallExtension.artifactPinning.enabled.get()

    private val isMavenInstallJsonExists
        get() = rootProject.file(artifactPinning.mavenInstallJson).exists()

    override fun mavenInstallJson(): String? = when {
        isMavenInstallJsonExists -> "//:" + artifactPinning.mavenInstallJson
        else -> null
    }

    /**
     * Determines the correct pinning target. i.e if maven_install.json already exists then `@maven//:pin` would not work,
     * so `@unpinned_maven//:pin` is chosen. Alternatively during first time when maven_install.json is not found in the repo
     * then `@maven//:pin` is used.
     */
    internal fun determinePinningTarget(): String {
        return when {
            isMavenInstallJsonExists -> MavenTargets.Unpinned.targetName
            else -> MavenTargets.Pinned.targetName
        }
    }

    override fun pin(workspaceFile: File) {
        if (isEnabled) {
            val outputStream = MavenPinningOutputStream(rootProject.logger)
            val firstRunResult = rootProject.bazelCommand(
                "run",
                determinePinningTarget(),
                "--noshow_progress",
                ignoreExit = true,
                errorOutputStream = outputStream
            )
            val errors = outputStream
                .errors
                .onEach { error -> rootProject.logger.quiet(error.message) }

            if (errors.any { it is InvalidSignature } || errors.any { it is JsonCorrupted }) {
                rootProject
                    .logger
                    .quiet("\nRetrying pinning to fix ${artifactPinning.mavenInstallJson}".ansiYellow)

                // Comment maven_install in WORKSPACE and run it again.
                val updatedWorkSpace = workspaceFile
                    .readText()
                    .replace("maven_install_json", "#maven_install_json")
                workspaceFile.writeText(updatedWorkSpace)

                // Run the pinning
                rootProject.bazelCommand(
                    "run",
                    MavenTargets.Pinned.targetName,
                    "--noshow_progress",
                )
                // Uncomment the maven install json
                workspaceFile.writeText(
                    updatedWorkSpace.replace(
                        "#maven_install_json",
                        "maven_install_json"
                    )
                )
            } else if (firstRunResult.exitValue != 0) {
                error("Artifact pinning failed, please see the logs for details")
            }
        }
    }
}