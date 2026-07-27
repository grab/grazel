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

package com.grab.grazel.gradle.dependencies

import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.bazel.starlark.BazelDependency.ProjectDependency
import com.grab.grazel.bazel.starlark.BazelDependency.StringDependency
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.dependencies.model.TargetReferenceFacts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TargetReferenceFactsCollectorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `collects repo and project references from target reference facts`() {
        val rootProject = buildProject("root")
        val appProject = buildProject("app", rootProject)
        val libProject = buildProject("lib", rootProject)

        val facts = TargetReferenceFactsCollector.from(
            deps = listOf(
                MavenDependency(group = "androidx.core", name = "core"),
                MavenDependency(
                    repo = "debug_maven",
                    group = "androidx.paging",
                    name = "paging-runtime"
                ),
                ProjectDependency(libProject, prefix = "prefix_", suffix = "_debug"),
                StringDependency("//feature/foo:feature_foo_debug"),
                StringDependency(":local_target")
            ),
            tags = listOf(
                "@android_test_maven//:androidx_test_core",
                "@rules_kotlin//kotlin:stdlib",
                "@maven//:androidx_core_core"
            ),
            plugins = listOf(
                MavenDependency(
                    repo = "ksp_maven",
                    group = "com.squareup.moshi",
                    name = "moshi-kotlin-codegen"
                )
            ),
            lintChecks = listOf(ProjectDependency(appProject, suffix = "_lint")),
            associates = listOf(StringDependency("//android/tests:android_tests_debug")),
            instruments = StringDependency("//app:app_debug")
        )

        assertEquals(
            setOf("debug_maven", "ksp_maven", "maven"),
            facts.repoNames
        )
        assertEquals(
            setOf(":android:tests", ":app", ":feature:foo", ":lib"),
            facts.projectPaths
        )
        assertEquals(
            mapOf(
                ":android:tests" to setOf("android_tests_debug"),
                ":app" to setOf("app_debug", "app_lint"),
                ":feature:foo" to setOf("feature_foo_debug"),
                ":lib" to setOf("prefix_lib_debug")
            ),
            facts.projectTargets
        )
    }

    @Test
    fun `collects structured Android test instrumented target reference`() {
        val rootProject = buildProject("root")
        val appProject = buildProject("app", rootProject)

        val facts = TargetReferenceFactsCollector.from(
            instruments = ProjectDependency(appProject, suffix = "-gps-pax-debug")
        )

        assertEquals(setOf(":app"), facts.projectPaths)
        assertEquals(mapOf(":app" to setOf("app-gps-pax-debug")), facts.projectTargets)
    }

    @Test
    fun `structured project references use Gradle path instead of rendered label path`() {
        val rootDir = temporaryFolder.newFolder("root")
        val rootProject = buildProject("root", projectDir = rootDir)
        val logicalProjectDir = rootDir.resolve("physical/location/logical")
        val logicalProject = buildProject(
            name = "logical",
            parent = rootProject,
            projectDir = logicalProjectDir
        )

        val facts = TargetReferenceFactsCollector.from(
            deps = listOf(ProjectDependency(logicalProject, prefix = "prefix_", suffix = "_debug"))
        )

        assertEquals(setOf(":logical"), facts.projectPaths)
        assertEquals(
            mapOf(":logical" to setOf("prefix_logical_debug")),
            facts.projectTargets
        )
    }

    @Test
    fun `collects maven artifact short ids grouped by repo`() {
        val facts = TargetReferenceFactsCollector.from(
            deps = listOf(
                MavenDependency(repo = "maven", group = "com.example", name = "core"),
                MavenDependency(repo = "maven", group = "com.example", name = "extras"),
                MavenDependency(repo = "demo_maven", group = "com.example", name = "demo-only")
            )
        )

        assertEquals(
            mapOf(
                "demo_maven" to setOf("com.example:demo-only"),
                "maven" to setOf("com.example:core", "com.example:extras")
            ),
            facts.mavenArtifacts
        )
    }

    @Test
    fun `compile filter tags contribute a repo name but no artifact`() {
        val facts = TargetReferenceFactsCollector.from(
            tags = listOf("@maven//:com_example_tagged")
        )

        assertEquals(setOf("maven"), facts.repoNames)
        assertEquals(emptyMap<String, Set<String>>(), facts.mavenArtifacts)
    }

    @Test
    fun `merging facts unions artifacts per repo`() {
        val left = TargetReferenceFacts(
            mavenArtifacts = mapOf("maven" to setOf("com.example:a"))
        )
        val right = TargetReferenceFacts(
            mavenArtifacts = mapOf(
                "maven" to setOf("com.example:b"),
                "test_maven" to setOf("com.example:c")
            )
        )

        assertEquals(
            mapOf(
                "maven" to setOf("com.example:a", "com.example:b"),
                "test_maven" to setOf("com.example:c")
            ),
            listOf(left, right).merged().mavenArtifacts
        )
    }
}
