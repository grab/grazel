/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.tasks.internal

import com.grab.grazel.bazel.starlark.writeToFile
import com.grab.grazel.di.GrazelComponent
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.MigrationChecker
import com.grab.grazel.migrate.internal.RootBazelFileBuilder
import com.grab.grazel.migrate.internal.WorkspaceBuilder
import com.grab.grazel.util.BUILD_BAZEL
import com.grab.grazel.util.WORKSPACE
import com.grab.grazel.util.ansiGreen
import com.grab.grazel.util.setFinal
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import javax.inject.Inject

internal open class GenerateRootBazelScriptsTask @Inject constructor(objectFactory: ObjectFactory) : DefaultTask() {
    @Input
    internal val migrationChecker = objectFactory.property<MigrationChecker>()

    @Input
    internal val workspaceBuilderFactory = objectFactory.property<WorkspaceBuilder.Factory>()

    @Input
    internal val rootBazelBuilder = objectFactory.property<RootBazelFileBuilder>()

    @TaskAction
    fun action() {
        val rootProject = project.rootProject
        val projectsToMigrate = rootProject
            .subprojects
            .filter { migrationChecker.get().canMigrate(it) }

        workspaceBuilderFactory.get()
            .create(projectsToMigrate)
            .build()
            .writeToFile(rootProject.file(WORKSPACE))
        logger.quiet("Generated WORKSPACE".ansiGreen)

        val rootBuildBazelContents = rootBazelBuilder.get().build()
        if (rootBuildBazelContents.isNotEmpty()) {
            rootBuildBazelContents.writeToFile(rootProject.file(BUILD_BAZEL))
            logger.quiet("Generated $BUILD_BAZEL".ansiGreen)
        }
    }

    companion object {
        private const val TASK_NAME = "generateRootBazelScripts"
        fun register(
            @RootProject rootProject: Project,
            grazelComponent: GrazelComponent
        ) = rootProject.tasks.register<GenerateRootBazelScriptsTask>(TASK_NAME) {
            migrationChecker.setFinal(grazelComponent.migrationChecker())
            workspaceBuilderFactory.setFinal(grazelComponent.workspaceBuilderFactory())
            rootBazelBuilder.setFinal(grazelComponent.rootBazelFileBuilder())
        }
    }
}
