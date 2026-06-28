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

import com.grab.grazel.buildProject
import com.grab.grazel.bazel.rules.Visibility
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.bazel.starlark.BazelDependency.ProjectDependency
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.migrate.BazelBuildTarget
import com.grab.grazel.migrate.BazelPluginTarget
import com.grab.grazel.migrate.android.AndroidTestTarget
import com.grab.grazel.migrate.android.LintConfigData
import com.grab.grazel.migrate.kotlin.KotlinLibraryTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetMavenRepoReferencesCollectorTest {

    @Test
    fun `collects Maven repos and project targets from target models`() {
        val rootProject = buildProject("root")
        val customLintRulesProject = buildProject("custom-lint-rules", rootProject)
        val uiTestsProject = buildProject("ui-tests", rootProject)
        val targets = listOf(
            fakeTarget(
                deps = listOf(
                    MavenDependency(group = "androidx.core", name = "core"),
                    MavenDependency(
                        repo = "debug_maven",
                        group = "androidx.paging",
                        name = "paging-runtime"
                    ),
                    ProjectDependency(uiTestsProject, suffix = "-gps-pax-debug_lib"),
                    BazelDependency.StringDependency("//sample-android-library")
                ),
                plugins = listOf(
                    MavenDependency(
                        repo = "ksp_maven",
                        group = "com.squareup.moshi",
                        name = "moshi-kotlin-codegen"
                    )
                ),
                tags = listOf(
                    "@lint_maven//:com_slack_lint_slack_lint_checks",
                    "@rules_kotlin//kotlin:stdlib"
                )
            ),
            KotlinLibraryTarget(
                name = "app",
                srcs = emptyList(),
                deps = emptyList(),
                res = emptyList(),
                lintConfigData = LintConfigData(
                    lintChecks = listOf(ProjectDependency(customLintRulesProject))
                )
            ),
        )

        val references = TargetMavenRepoReferencesCollector.fromTargets(targets)

        assertEquals(
            setOf("debug_maven", "ksp_maven", "maven"),
            references.repoNames
        )
        assertEquals(
            setOf(":custom-lint-rules", ":ui-tests"),
            references.projectPaths
        )
        assertEquals(
            mapOf(
                ":custom-lint-rules" to setOf("custom-lint-rules"),
                ":ui-tests" to setOf("ui-tests-gps-pax-debug_lib")
            ),
            references.projectTargets
        )
    }

    @Test
    fun `collects Android test associates and instrumented target references`() {
        val rootProject = buildProject("root")
        val appProject = buildProject("app", rootProject)
        val appTestProject = buildProject("app-test", rootProject)
        val uiTestsProject = buildProject("ui-tests", rootProject)
        val target = AndroidTestTarget(
            name = "ui-tests-gps-pax-debug",
            deps = listOf(ProjectDependency(appTestProject, suffix = "-gps-pax-debug")),
            associates = listOf(ProjectDependency(appProject, suffix = "-gps-pax-debug")),
            instruments = BazelDependency.StringDependency("//app:app-gps-pax-debug"),
            packageName = "com.example.uitests",
            customPackage = "com.example.uitests",
            targetPackage = "com.example.app",
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner",
            manifestValues = emptyMap(),
            debugKey = null,
            resources = emptyList(),
            resourceFiles = emptyList(),
            resourceStripPrefix = null,
        )
        val referencingTarget = fakeTarget(
            deps = listOf(ProjectDependency(uiTestsProject, suffix = "-gps-pax-debug_lib"))
        )

        val references = TargetMavenRepoReferencesCollector.fromTargets(
            listOf(target, referencingTarget)
        )

        assertEquals(
            setOf(":app", ":app-test", ":ui-tests"),
            references.projectPaths
        )
        assertEquals(
            mapOf(
                ":app" to setOf("app-gps-pax-debug"),
                ":app-test" to setOf("app-test-gps-pax-debug"),
                ":ui-tests" to setOf("ui-tests-gps-pax-debug_lib")
            ),
            references.projectTargets
        )
    }

    @Test
    fun `collects structured Android test instrumented target reference`() {
        val rootProject = buildProject("root")
        val appProject = buildProject("app", rootProject)
        val target = AndroidTestTarget(
            name = "ui-tests-gps-pax-debug",
            deps = emptyList(),
            associates = emptyList(),
            instruments = ProjectDependency(appProject, suffix = "-gps-pax-debug"),
            packageName = "com.example.uitests",
            customPackage = "com.example.uitests",
            targetPackage = "com.example.app",
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner",
            manifestValues = emptyMap(),
            debugKey = null,
            resources = emptyList(),
            resourceFiles = emptyList(),
            resourceStripPrefix = null,
        )

        val references = TargetMavenRepoReferencesCollector.fromTargets(listOf(target))

        assertEquals(setOf(":app"), references.projectPaths)
        assertEquals(mapOf(":app" to setOf("app-gps-pax-debug")), references.projectTargets)
    }

    private fun fakeTarget(
        deps: List<BazelDependency> = emptyList(),
        plugins: List<BazelDependency> = emptyList(),
        tags: List<String> = emptyList()
    ): BazelBuildTarget {
        return object : BazelBuildTarget, BazelPluginTarget {
            override val name: String = "fake"
            override val srcs: List<String> = emptyList()
            override val deps: List<BazelDependency> = deps
            override val visibility: Visibility = Visibility.Public
            override val tags: List<String> = tags
            override val plugins: List<BazelDependency> = plugins
            override val sortKey: String = name
            override fun statements(builder: StatementsBuilder) = Unit
        }
    }
}
