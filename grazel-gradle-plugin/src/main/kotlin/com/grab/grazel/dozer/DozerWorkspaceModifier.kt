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

package com.grab.grazel.dozer

import com.grab.grazel.GrazelExtension
import com.grab.grazel.gradle.DefaultGradleProjectInfo
import com.grab.grazel.migrate.WorkspaceModifier
import org.gradle.api.logging.Logger
import org.gradle.process.internal.ExecException

internal fun createDozerWorkspaceModifier(
    gradleProjectInfo: DefaultGradleProjectInfo,
    extension: GrazelExtension
): WorkspaceModifier {
    val dozerUpdates = listOf<DozerUpdate>(
        AddedMavenDependency(gradleProjectInfo.rootProject),
        ReplaceMavenDependency(gradleProjectInfo.rootProject)
    )
    val tempFileManager = DefaultTempFileManager(gradleProjectInfo.rootProject.rootDir)
    val bazelDependencyAnalytics = QueryBazelDependencyAnalytics(gradleProjectInfo, extension)
    return DozerWorkspaceModifier(
        dozerUpdates, tempFileManager,
        bazelDependencyAnalytics, gradleProjectInfo.rootProject.logger
    )
}

private class DozerWorkspaceModifier(
    private val dozerUpdates: List<DozerUpdate>,
    private val tempFileManager: TempFileManager,
    private val dependencyAnalytics: BazelDependencyAnalytics,
    private val logger: Logger
) : WorkspaceModifier {

    override fun process() {
        logger.quiet("WORKSPACE is being processed")
        try {
            tempFileManager.workSpaceFileToTempFile()
            dozerUpdates.forEach {
                it.update(dependencyAnalytics)
            }
            tempFileManager.tempFileToWorkSpaceFile()
        } catch (e: ExecException) {
            logger.error("If it's a buildozer failed command, make sure you set name=\"maven\" to maven_install rule")
            throw e
        } finally {
            tempFileManager.deleteTempDir()
        }
        logger.quiet("WORKSPACE file has been modified")
    }
}

