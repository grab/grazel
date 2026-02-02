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
import com.grab.grazel.migrate.android.AndroidLibraryData
import com.grab.grazel.migrate.android.BazelSourceSet
import com.grab.grazel.migrate.android.BuildConfigData
import com.grab.grazel.migrate.android.LintConfigData
import com.grab.grazel.migrate.android.ResValuesData
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VariantEquivalenceCheckerTest : GrazelPluginTest() {

    private lateinit var checker: VariantEquivalenceChecker
    private lateinit var normalizer: DependencyNormalizer
    private lateinit var project: Project

    @Before
    fun setup() {
        normalizer = DefaultDependencyNormalizer()
        checker = DefaultVariantEquivalenceChecker(normalizer)
        project = buildProject("root")
    }

    private fun createBasicData(name: String): AndroidLibraryData {
        return AndroidLibraryData(
            name = name,
            srcs = listOf("src/main/kotlin/**/*.kt"),
            resourceSets = emptySet(),
            resValuesData = ResValuesData(),
            manifestFile = "src/main/AndroidManifest.xml",
            customPackage = "com.example",
            packageName = "com.example.app",
            buildConfigData = BuildConfigData(),
            deps = emptyList(),
            plugins = emptyList(),
            databinding = false,
            compose = false,
            tags = emptyList(),
            lintConfigData = LintConfigData()
        )
    }

    @Test
    fun `areEquivalent returns true for identical variants`() {
        val first = createBasicData("variant1")
        val second = createBasicData("variant2")

        assertTrue(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent returns false when srcs differ`() {
        val first = createBasicData("variant1").copy(
            srcs = listOf("src/main/kotlin/**/*.kt")
        )
        val second = createBasicData("variant2").copy(
            srcs = listOf("src/main/java/**/*.java")
        )

        assertFalse(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent returns false when resourceSets differ`() {
        val first = createBasicData("variant1").copy(
            resourceSets = setOf(BazelSourceSet("main", "res", null, null))
        )
        val second = createBasicData("variant2").copy(
            resourceSets = setOf(BazelSourceSet("main", "res", "assets", null))
        )

        assertFalse(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent returns false when manifestFile differs`() {
        val first = createBasicData("variant1").copy(
            manifestFile = "src/main/AndroidManifest.xml"
        )
        val second = createBasicData("variant2").copy(
            manifestFile = "src/debug/AndroidManifest.xml"
        )

        assertFalse(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent returns false when packageName differs`() {
        val first = createBasicData("variant1").copy(
            packageName = "com.example.app"
        )
        val second = createBasicData("variant2").copy(
            packageName = "com.example.debug"
        )

        assertFalse(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent returns false when customPackage differs`() {
        val first = createBasicData("variant1").copy(
            customPackage = "com.example"
        )
        val second = createBasicData("variant2").copy(
            customPackage = "com.example.debug"
        )

        assertFalse(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent returns false when buildConfigData differs`() {
        val first = createBasicData("variant1").copy(
            buildConfigData = BuildConfigData(
                strings = mapOf("API_URL" to "\"https://prod.example.com\"")
            )
        )
        val second = createBasicData("variant2").copy(
            buildConfigData = BuildConfigData(
                strings = mapOf("API_URL" to "\"https://debug.example.com\"")
            )
        )

        assertFalse(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent returns false when resValuesData differs`() {
        val first = createBasicData("variant1").copy(
            resValuesData = ResValuesData(
                stringValues = mapOf("app_name" to "App")
            )
        )
        val second = createBasicData("variant2").copy(
            resValuesData = ResValuesData(
                stringValues = mapOf("app_name" to "App Debug")
            )
        )

        assertFalse(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent ignores databinding differences`() {
        val first = createBasicData("variant1").copy(databinding = true)
        val second = createBasicData("variant2").copy(databinding = false)

        assertTrue(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent ignores compose differences`() {
        val first = createBasicData("variant1").copy(compose = true)
        val second = createBasicData("variant2").copy(compose = false)

        assertTrue(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent ignores plugins differences`() {
        val first = createBasicData("variant1").copy(
            plugins = listOf(BazelDependency.StringDependency("//tools:kotlin"))
        )
        val second = createBasicData("variant2").copy(
            plugins = emptyList()
        )

        assertTrue(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent ignores tags differences`() {
        val first = createBasicData("variant1").copy(
            tags = listOf("manual")
        )
        val second = createBasicData("variant2").copy(
            tags = listOf("manual", "local")
        )

        assertTrue(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent ignores lintConfigData differences`() {
        val first = createBasicData("variant1").copy(
            lintConfigData = LintConfigData(enabled = true)
        )
        val second = createBasicData("variant2").copy(
            lintConfigData = LintConfigData(enabled = false)
        )

        assertTrue(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent returns true when deps are equivalent after normalization`() {
        val dependency = buildProject("library", parent = project)

        val first = createBasicData("variant1").copy(
            deps = listOf(
                BazelDependency.ProjectDependency(dependency, suffix = "-free-debug")
            )
        )
        val second = createBasicData("variant2").copy(
            deps = listOf(
                BazelDependency.ProjectDependency(dependency, suffix = "-paid-debug")
            )
        )

        assertTrue(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent returns false when deps point to different projects`() {
        val lib1 = buildProject("library1", parent = project)
        val lib2 = buildProject("library2", parent = project)

        val first = createBasicData("variant1").copy(
            deps = listOf(
                BazelDependency.ProjectDependency(lib1, suffix = "-debug")
            )
        )
        val second = createBasicData("variant2").copy(
            deps = listOf(
                BazelDependency.ProjectDependency(lib2, suffix = "-debug")
            )
        )

        assertFalse(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent returns false when deps count differs`() {
        val dependency = buildProject("library", parent = project)

        val first = createBasicData("variant1").copy(
            deps = listOf(
                BazelDependency.ProjectDependency(dependency, suffix = "-debug")
            )
        )
        val second = createBasicData("variant2").copy(
            deps = emptyList()
        )

        assertFalse(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent returns true when deps are in different order but normalize to same`() {
        val lib1 = buildProject("library1", parent = project)
        val lib2 = buildProject("library2", parent = project)

        val first = createBasicData("variant1").copy(
            deps = listOf(
                BazelDependency.ProjectDependency(lib1, suffix = "-debug"),
                BazelDependency.ProjectDependency(lib2, suffix = "-debug")
            )
        )
        val second = createBasicData("variant2").copy(
            deps = listOf(
                BazelDependency.ProjectDependency(lib2, suffix = "-debug"),
                BazelDependency.ProjectDependency(lib1, suffix = "-debug")
            )
        )

        assertTrue(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent handles maven dependencies`() {
        val first = createBasicData("variant1").copy(
            deps = listOf(
                BazelDependency.MavenDependency(
                    repo = "maven",
                    group = "com.google.guava",
                    name = "guava"
                )
            )
        )
        val second = createBasicData("variant2").copy(
            deps = listOf(
                BazelDependency.MavenDependency(
                    repo = "maven",
                    group = "com.google.guava",
                    name = "guava"
                )
            )
        )

        assertTrue(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent returns false for different maven dependencies`() {
        val first = createBasicData("variant1").copy(
            deps = listOf(
                BazelDependency.MavenDependency(
                    repo = "maven",
                    group = "com.google.guava",
                    name = "guava"
                )
            )
        )
        val second = createBasicData("variant2").copy(
            deps = listOf(
                BazelDependency.MavenDependency(
                    repo = "maven",
                    group = "androidx.core",
                    name = "core-ktx"
                )
            )
        )

        assertFalse(checker.areEquivalent(first, second))
    }

    @Test
    fun `areEquivalent handles mixed dependency types`() {
        val lib = buildProject("library", parent = project)

        val first = createBasicData("variant1").copy(
            deps = listOf(
                BazelDependency.ProjectDependency(lib, suffix = "-free-debug"),
                BazelDependency.MavenDependency("maven", "com.google.guava", "guava"),
                BazelDependency.StringDependency("//external:dep")
            )
        )
        val second = createBasicData("variant2").copy(
            deps = listOf(
                BazelDependency.ProjectDependency(lib, suffix = "-paid-debug"),
                BazelDependency.MavenDependency("maven", "com.google.guava", "guava"),
                BazelDependency.StringDependency("//external:dep")
            )
        )

        assertTrue(checker.areEquivalent(first, second))
    }
}
