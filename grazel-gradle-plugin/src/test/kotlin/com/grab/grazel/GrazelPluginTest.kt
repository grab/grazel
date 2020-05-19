/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import java.io.File

abstract class GrazelPluginTest {
}

internal fun buildProject(
    name: String,
    parent: Project? = null,
    projectDir: File? = null,
    builder: ProjectBuilder.() -> Unit = {}
): Project = ProjectBuilder
    .builder()
    .withName(name)
    .let { projectBuilder -> projectDir?.let { projectBuilder.withProjectDir(projectDir) } ?: projectBuilder }
    .let { projectBuilder -> parent?.let { projectBuilder.withParent(parent) } ?: projectBuilder }
    .apply(builder)
    .build()