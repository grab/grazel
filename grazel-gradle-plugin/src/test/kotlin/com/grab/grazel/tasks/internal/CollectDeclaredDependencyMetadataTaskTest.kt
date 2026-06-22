/*
 * Copyright 2026 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.tasks.internal

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CollectDeclaredDependencyMetadataTaskTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `dependency declaration inputs exclude generated output trees`() {
        val projectDir = temporaryFolder.newFolder("project")
        val rootBuildFile = projectDir.resolve("build.gradle").apply {
            writeText("plugins {}")
        }
        val moduleBuildFile = projectDir.resolve("app/build.gradle.kts").apply {
            parentFile.mkdirs()
            writeText("plugins {}")
        }
        val versionCatalog = projectDir.resolve("gradle/libs.versions.toml").apply {
            parentFile.mkdirs()
            writeText("[versions]")
        }

        projectDir.resolve(".gradle/generated.gradle").apply {
            parentFile.mkdirs()
            writeText("plugins {}")
        }
        projectDir.resolve("app/build/generated.gradle").apply {
            parentFile.mkdirs()
            writeText("plugins {}")
        }
        projectDir.resolve("bazel-grazel/external/test_maven/generated.gradle").apply {
            parentFile.mkdirs()
            writeText("plugins {}")
        }

        val project = ProjectBuilder.builder()
            .withProjectDir(projectDir)
            .build()
        val canonicalProjectDir = projectDir.canonicalFile

        val files = CollectDeclaredDependencyMetadataTask
            .dependencyDeclarationFileTree(project)
            .files
            .mapTo(sortedSetOf()) { file ->
                file.canonicalFile.relativeTo(canonicalProjectDir).invariantSeparatorsPath
            }

        assertEquals(
            sortedSetOf(
                rootBuildFile.canonicalFile.relativeTo(canonicalProjectDir).invariantSeparatorsPath,
                moduleBuildFile.canonicalFile.relativeTo(canonicalProjectDir).invariantSeparatorsPath,
                versionCatalog.canonicalFile.relativeTo(canonicalProjectDir).invariantSeparatorsPath
            ),
            files
        )
    }
}
