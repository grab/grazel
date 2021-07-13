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
    .let { projectBuilder ->
        projectDir?.let { projectBuilder.withProjectDir(projectDir) } ?: projectBuilder
    }.let { projectBuilder -> parent?.let { projectBuilder.withParent(parent) } ?: projectBuilder }
    .apply(builder)
    .build()