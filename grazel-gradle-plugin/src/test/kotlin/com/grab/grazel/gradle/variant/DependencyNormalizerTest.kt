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

package com.grab.grazel.gradle.variant

import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.buildProject
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class DependencyNormalizerTest : GrazelPluginTest() {

    private lateinit var normalizer: DependencyNormalizer
    private lateinit var project: Project

    @Before
    fun setup() {
        normalizer = DefaultDependencyNormalizer()
        project = buildProject("root")
    }

    @Test
    fun `normalize ProjectDependency with variant suffix`() {
        val subProject = buildProject("library", parent = project)
        val dependency = BazelDependency.ProjectDependency(
            dependencyProject = subProject,
            suffix = "-free-debug"
        )

        val result = normalizer.normalize(dependency)

        assertEquals("//library:library", result)
    }

    @Test
    fun `normalize ProjectDependency with _kt suffix`() {
        val subProject = buildProject("library", parent = project)
        val dependency = BazelDependency.ProjectDependency(
            dependencyProject = subProject,
            suffix = "_kt-debug"
        )

        val result = normalizer.normalize(dependency)

        assertEquals("//library:library", result)
    }

    @Test
    fun `normalize ProjectDependency with _lib suffix`() {
        val subProject = buildProject("library", parent = project)
        val dependency = BazelDependency.ProjectDependency(
            dependencyProject = subProject,
            suffix = "_lib-paid-release"
        )

        val result = normalizer.normalize(dependency)

        assertEquals("//library:library", result)
    }

    @Test
    fun `normalize ProjectDependency with prefix`() {
        val subProject = buildProject("library", parent = project)
        val dependency = BazelDependency.ProjectDependency(
            dependencyProject = subProject,
            prefix = "android_",
            suffix = "-debug"
        )

        val result = normalizer.normalize(dependency)

        // Prefix should also be removed in normalization
        assertEquals("//library:library", result)
    }

    @Test
    fun `normalize ProjectDependency without suffix`() {
        val subProject = buildProject("library", parent = project)
        val dependency = BazelDependency.ProjectDependency(
            dependencyProject = subProject,
            suffix = ""
        )

        val result = normalizer.normalize(dependency)

        assertEquals("//library:library", result)
    }

    @Test
    fun `normalize nested ProjectDependency`() {
        // Create a nested project structure
        val parentProject = buildProject("parent", parent = project)
        val nestedProject = buildProject("nested", parent = parentProject)

        val dependency = BazelDependency.ProjectDependency(
            dependencyProject = nestedProject,
            suffix = "-free-debug"
        )

        val result = normalizer.normalize(dependency)

        assertEquals("//parent/nested:nested", result)
    }

    @Test
    fun `normalize MavenDependency`() {
        val dependency = BazelDependency.MavenDependency(
            repo = "maven",
            group = "com.google.guava",
            name = "guava"
        )

        val result = normalizer.normalize(dependency)

        assertEquals("@maven//:com_google_guava_guava", result)
    }

    @Test
    fun `normalize MavenDependency with dashes`() {
        val dependency = BazelDependency.MavenDependency(
            repo = "maven",
            group = "androidx.core",
            name = "core-ktx"
        )

        val result = normalizer.normalize(dependency)

        assertEquals("@maven//:androidx_core_core_ktx", result)
    }

    @Test
    fun `normalize MavenDependency with custom repo`() {
        val dependency = BazelDependency.MavenDependency(
            repo = "internal",
            group = "com.grab",
            name = "library"
        )

        val result = normalizer.normalize(dependency)

        assertEquals("@internal//:com_grab_library", result)
    }

    @Test
    fun `normalize StringDependency`() {
        val dependency = BazelDependency.StringDependency("//some:target")

        val result = normalizer.normalize(dependency)

        assertEquals("//some:target", result)
    }

    @Test
    fun `normalize StringDependency with whitespace`() {
        val dependency = BazelDependency.StringDependency("  //some:target  ")

        val result = normalizer.normalize(dependency)

        assertEquals("//some:target", result)
    }

    @Test
    fun `normalize FileDependency in root`() {
        val file = File(project.projectDir, "libs.jar")
        val dependency = BazelDependency.FileDependency(
            file = file,
            rootProject = project
        )

        val result = normalizer.normalize(dependency)

        assertEquals("//:libs.jar", result)
    }

    @Test
    fun `normalize FileDependency in subdirectory`() {
        val libsDir = File(project.projectDir, "libs")
        val file = File(libsDir.absolutePath, "custom.jar")
        val dependency = BazelDependency.FileDependency(
            file = file,
            rootProject = project
        )

        val result = normalizer.normalize(dependency)

        assertEquals("//libs:custom.jar", result)
    }

    @Test
    fun `normalize different variants produce same result`() {
        val subProject = buildProject("library", parent = project)

        val freeDebug = BazelDependency.ProjectDependency(
            dependencyProject = subProject,
            suffix = "-free-debug"
        )
        val paidDebug = BazelDependency.ProjectDependency(
            dependencyProject = subProject,
            suffix = "-paid-debug"
        )
        val release = BazelDependency.ProjectDependency(
            dependencyProject = subProject,
            suffix = "-release"
        )

        val result1 = normalizer.normalize(freeDebug)
        val result2 = normalizer.normalize(paidDebug)
        val result3 = normalizer.normalize(release)

        assertEquals(result1, result2)
        assertEquals(result2, result3)
        assertEquals("//library:library", result1)
    }
}
