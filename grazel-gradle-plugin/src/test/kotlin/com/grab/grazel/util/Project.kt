/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.util

import com.grab.grazel.di.DaggerGrazelComponent
import com.grab.grazel.di.GrazelComponent
import com.grab.grazel.gradle.GradleProjectInfo
import org.gradle.api.Project

/**
 * Forces a evaluation of the project thereby running all configurations
 */
fun Project.doEvaluate() = getTasksByName("tasks", false)

internal fun Project.createGrazelComponent(): GrazelComponent {
    return DaggerGrazelComponent.factory().create(this)
}

internal fun createProjectInfo(rootProject: Project): GradleProjectInfo {
    return rootProject.createGrazelComponent().gradleProjectInfo()
}