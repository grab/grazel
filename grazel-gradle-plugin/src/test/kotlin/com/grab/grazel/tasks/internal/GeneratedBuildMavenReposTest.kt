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

import com.grab.grazel.bazel.rules.Visibility
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.migrate.BazelBuildTarget
import com.grab.grazel.migrate.BazelPluginTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeneratedBuildMavenReposTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `collects Maven repos from generated targets`() {
        val targets = listOf(
            fakeTarget(
                deps = listOf(
                    MavenDependency(group = "androidx.core", name = "core"),
                    MavenDependency(
                        repo = "debug_maven",
                        group = "androidx.paging",
                        name = "paging-runtime"
                    ),
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
            )
        )

        assertEquals(
            setOf("debug_maven", "ksp_maven", "lint_maven", "maven"),
            GeneratedBuildMavenRepos.fromTargets(targets)
        )
    }

    @Test
    fun `reads stable referenced repo manifests`() {
        val manifest = temporaryFolder.newFile("referenced-maven-repos.txt")

        GeneratedBuildMavenRepos.writeManifest(
            file = manifest,
            repos = setOf("maven", "debug_maven")
        )

        assertEquals(
            "debug_maven\nmaven\n",
            manifest.readText()
        )
        assertEquals(
            setOf("debug_maven", "maven"),
            GeneratedBuildMavenRepos.fromFiles(listOf(manifest))
        )
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
