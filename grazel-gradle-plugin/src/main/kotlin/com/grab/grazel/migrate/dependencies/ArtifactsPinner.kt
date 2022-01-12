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
import org.gradle.api.Project
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
    fun pin()
}

@Singleton
class DefaultArtifactsPinner
@Inject
constructor(
    @param:RootProject private val rootProject: Project,
    private val mavenInstallExtension: MavenInstallExtension
) : ArtifactsPinner {

    private val artifactPinning get() = mavenInstallExtension.artifactPinning

    override val isEnabled: Boolean
        get() = mavenInstallExtension.artifactPinning.enabled.get()

    private val isMavenInstallJsonExists get() = rootProject.file(artifactPinning.mavenInstallJson).exists()

    override fun mavenInstallJson(): String? = if (isMavenInstallJsonExists) {
        "//:" + artifactPinning.mavenInstallJson
    } else null

    /**
     * Determines the correct pinning target. i.e if maven_install.json already exists then `@maven//:pin` would not work,
     * so `@unpinned_maven//:pin` is chosen. Alternatively during first time when maven_install.json is not found in the repo
     * then `@maven//:pin` is used.
     */
    internal fun determinePinningTarget(): String {
        return when {
            isMavenInstallJsonExists -> "@unpinned_maven//:pin"
            else -> "@maven//:pin"
        }
    }

    override fun pin() {
        if (isEnabled) {
            rootProject.bazelCommand(
                "run",
                determinePinningTarget(),
                "--noshow_progress"
            )
        }
    }
}