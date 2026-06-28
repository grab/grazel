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
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetReferenceFactsCollectorTest {

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
            setOf("android_test_maven", "debug_maven", "ksp_maven", "maven"),
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
}
