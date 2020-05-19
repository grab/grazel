/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
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

