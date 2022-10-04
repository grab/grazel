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

package com.grab.grazel.migrate.android

import com.android.build.gradle.LibraryExtension
import com.google.common.truth.Truth
import com.grab.grazel.GrazelExtension
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.buildProject
import com.grab.grazel.fake.FakeDependencyGraphs
import com.grab.grazel.gradle.ANDROID_LIBRARY_PLUGIN
import com.grab.grazel.gradle.DefaultAndroidVariantDataSource
import com.grab.grazel.gradle.DefaultAndroidVariantsExtractor
import com.grab.grazel.gradle.DefaultConfigurationDataSource
import com.grab.grazel.gradle.DefaultRepositoryDataSource
import com.grab.grazel.gradle.FakeAndroidVariantsExtractor
import com.grab.grazel.gradle.KOTLIN_ANDROID_PLUGIN
import com.grab.grazel.gradle.dependencies.ArtifactsConfig
import com.grab.grazel.gradle.dependencies.DefaultDependenciesDataSource
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.GradleDependencyToBazelDependency
import com.grab.grazel.util.doEvaluate
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.the
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private const val KOTLIN_STDLIB = "org.jetbrains.kotlin:kotlin-stdlib"

class DefaultAndroidUnitTestDataExtractorTest : GrazelPluginTest() {
    private lateinit var rootProject: Project
    private lateinit var subProject: Project
    private lateinit var subProjectDir: File

    private lateinit var defaultAndroidUnitTestDataExtractor: DefaultAndroidUnitTestDataExtractor
    private lateinit var androidVariantsExtractor: FakeAndroidVariantsExtractor
    private lateinit var gradleDependencyToBazelDependency: GradleDependencyToBazelDependency

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setup() {
        val rootProjectDir = temporaryFolder.newFolder("project")
        rootProject = buildProject("root", projectDir = rootProjectDir)

        subProjectDir = File(rootProjectDir, "subproject").apply {
            mkdirs()
        }
        subProject = buildProject("subproject", rootProject)
        subProject.run {
            plugins.apply {
                apply(ANDROID_LIBRARY_PLUGIN)
                apply(KOTLIN_ANDROID_PLUGIN)
            }
            extensions.configure<LibraryExtension> {
                defaultConfig {
                    compileSdkVersion(30)
                }
            }
        }
        androidVariantsExtractor = FakeAndroidVariantsExtractor()
        gradleDependencyToBazelDependency = GradleDependencyToBazelDependency()
        File(subProjectDir, "src/main/AndroidManifest.xml").apply {
            parentFile.mkdirs()
            createNewFile()
            writeText(
                """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                        package="com.example.androidlibrary">
                    </manifest>
                """.trimIndent()
            )
        }

        val variantDataSource = DefaultAndroidVariantDataSource(DefaultAndroidVariantsExtractor())
        val configurationDataSource = DefaultConfigurationDataSource(variantDataSource)
        val repositoryDataSource = DefaultRepositoryDataSource(rootProject)

        val dependenciesDataSource = DefaultDependenciesDataSource(
            rootProject = rootProject,
            configurationDataSource = configurationDataSource,
            artifactsConfig = ArtifactsConfig(ignoredList = listOf(KOTLIN_STDLIB)),
            repositoryDataSource = repositoryDataSource,
            dependencyResolutionService = DefaultDependencyResolutionService.register(rootProject),
            grazelExtension = GrazelExtension(rootProject),
            androidVariantsExtractor = androidVariantsExtractor
        )

        val dependencyGraphs = FakeDependencyGraphs()
        val androidManifestParser: AndroidManifestParser = DefaultAndroidManifestParser()

        defaultAndroidUnitTestDataExtractor = DefaultAndroidUnitTestDataExtractor(
            dependenciesDataSource = dependenciesDataSource,
            variantDataSource = variantDataSource,
            dependencyGraphsProvider = { dependencyGraphs },
            androidManifestParser = androidManifestParser,
            grazelExtension = GrazelExtension(rootProject),
            gradleDependencyToBazelDependency = gradleDependencyToBazelDependency
        )
    }

    @Test
    fun `assert single test resource is parsed correctly`() {
        File(subProjectDir, "src/test/resources/test.json").apply {
            parentFile.mkdirs()
            createNewFile()
        }

        subProject.doEvaluate()

        val androidUnitTestData = defaultAndroidUnitTestDataExtractor.extract(
            subProject,
            debugUnitTestVariant(subProject)
        )
        Truth.assertThat(androidUnitTestData.resources).apply {
            contains("src/test/resources/test.json")
        }
    }

    @Test
    fun `assert test resources are parsed correctly`() {
        File(subProjectDir, "src/test/resources/test1.json").apply {
            parentFile.mkdirs()
            createNewFile()
        }

        File(subProjectDir, "src/test/resources/test2.json").apply {
            parentFile.mkdirs()
            createNewFile()
        }

        subProject.doEvaluate()
        val androidUnitTestData = defaultAndroidUnitTestDataExtractor.extract(
            subProject,
            debugUnitTestVariant(subProject)
        )
        Truth.assertThat(androidUnitTestData.resources).apply {
            contains("src/test/resources/**")
        }
    }

    @Test
    fun `assert additional source sets are extracted correctly`() {
        val defaultTestSourceSets = listOf("src/test/java", "src/test/kotlin")
        val additionalTestSourceSets = listOf("src/testDebug/java")

        additionalTestSourceSets.map { sourceSet ->
            File(subProjectDir, "$sourceSet/Test.kt")
        }.forEach { file ->
            file.parentFile.mkdirs()
            file.createNewFile()
        }

        subProject.doEvaluate()

        val androidUnitTestData = defaultAndroidUnitTestDataExtractor.extract(
            subProject,
            debugUnitTestVariant(subProject)
        )
        Truth.assertThat(androidUnitTestData.additionalSrcSets).apply {
            containsExactlyElementsIn(additionalTestSourceSets)
            containsNoneIn(defaultTestSourceSets)
        }
    }

    private fun debugUnitTestVariant(project: Project): MergedVariant {
        val variant = project.the<LibraryExtension>().unitTestVariants.first {
            it.name == "debugUnitTest"
        }

        return MergedVariant(
            variant.flavorName,
            variant.buildType.name,
            variant
        )
    }
}
