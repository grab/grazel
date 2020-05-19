/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel

import com.grab.grazel.GrazelExtension.Companion.GRAZEL_EXTENSION
import com.grab.grazel.di.DaggerGrazelComponent
import com.grab.grazel.hybrid.doHybridBuild
import com.grab.grazel.tasks.internal.TaskManager
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create

class GrazelGradlePlugin : Plugin<Project> {
    private lateinit var project: Project

    override fun apply(project: Project) {
        this.project = project
        if (project != project.rootProject) {
            throw IllegalStateException("Grazel should be only applied to root build.gradle")
        }
        project.extensions.create<GrazelExtension>(GRAZEL_EXTENSION, project)

        val grazelComponent = DaggerGrazelComponent.factory().create(project)

        TaskManager(project, grazelComponent).configTasks()
        project.doHybridBuild()
    }
}