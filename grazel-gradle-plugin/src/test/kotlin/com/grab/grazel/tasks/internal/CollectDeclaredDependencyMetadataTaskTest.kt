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

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CollectDeclaredDependencyMetadataTaskTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `declared dependency metadata task consumes stable metadata json input`() {
        val taskGetterNames = CollectDeclaredDependencyMetadataTask::class.java.methods
            .mapTo(mutableSetOf()) { method -> method.name }
        val metadataJsonGetter = CollectDeclaredDependencyMetadataTask::class.java
            .getMethod("getDeclaredDependencyMetadataJson")

        assertFalse(
            "Declared metadata should be computed before the task action and supplied as a stable " +
                "input, not through a hidden MigrationChecker service property.",
            "getMigrationCheckerProvider" in taskGetterNames
        )
        assertFalse(
            "Declared metadata should be computed before the task action and supplied as a stable " +
                "input, not through a hidden VariantBuilder service property.",
            "getVariantBuilderProvider" in taskGetterNames
        )
        assertTrue(
            "Serialized declared metadata must participate in the task cache key.",
            metadataJsonGetter.isAnnotationPresent(Input::class.java)
        )
        assertFalse(
            "Serialized declared metadata should not be hidden from Gradle caching.",
            metadataJsonGetter.isAnnotationPresent(Internal::class.java)
        )
    }

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
