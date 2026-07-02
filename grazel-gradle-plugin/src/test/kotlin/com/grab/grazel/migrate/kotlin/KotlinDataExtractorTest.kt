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

package com.grab.grazel.migrate.kotlin

import com.grab.grazel.GrazelExtension
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.KOTLIN_PLUGIN
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.GradleDependencyToBazelDependency
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.migrate.common.TestSizeCalculator
import com.grab.grazel.util.GradleProvider
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import com.grab.grazel.util.initDependencyGraphsForTest
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.repositories
import org.gradle.kotlin.dsl.the
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertTrue

class KotlinDataExtractorTest : GrazelPluginTest() {
    private lateinit var rootProject: Project
    private lateinit var kotlinProject: Project
    private lateinit var kotlinProjectDataExtractor: DefaultKotlinProjectDataExtractor
    private lateinit var kotlinUnitTestDataExtractor: DefaultKotlinUnitTestDataExtractor
    private lateinit var dependencyResolutionService: GradleProvider<DefaultDependencyResolutionService>

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setup() {
        val rootProjectDir = temporaryFolder.newFolder("project")
        rootProject = buildProject("root", projectDir = rootProjectDir).also { root ->
            root.addGrazelExtension {
                rules.kotlin.enabledTransitiveReduction = true
            }
        }
        val kotlinProjectDir = File(rootProjectDir, "kotlin-lib").apply { mkdirs() }
        kotlinProject = buildProject("kotlin-lib", rootProject, projectDir = kotlinProjectDir).also { project ->
            project.plugins.apply(KOTLIN_PLUGIN)
            project.repositories {
                google()
                mavenCentral()
            }
            project.dependencies {
                add("implementation", "com.example:main-root:1.0")
                add("testImplementation", "com.example:test-root:1.0")
            }
        }
        File(kotlinProjectDir, "src/main/kotlin/com/example/Lib.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nclass Lib\n")
        }
        File(kotlinProjectDir, "src/test/kotlin/com/example/LibTest.kt").apply {
            parentFile.mkdirs()
            writeText("package com.example\nclass LibTest\n")
        }

        kotlinProject.doEvaluate()
        val component = rootProject.createGrazelComponent()
        component.initDependencyGraphsForTest(rootProject)
        dependencyResolutionService = component.dependencyResolutionService()
        dependencyResolutionService.get().populateCache(
            WorkspaceDependencies(
                variantDeps = mapOf(
                    DEFAULT_VARIANT to listOf(
                        ResolvedDependency.fromId("com.example:main-root:1.0", DEFAULT_VARIANT)
                    ),
                    "test" to listOf(
                        ResolvedDependency.fromId("com.example:test-root:1.0", "test")
                    )
                ),
                transitiveClasspath = mapOf(
                    "com.example:main-root" to setOf("com.example:main-child"),
                    "com.example:test-root" to setOf("com.example:test-child")
                )
            )
        )

        val extension = rootProject.the<GrazelExtension>()
        val gradleDependencyToBazelDependency = GradleDependencyToBazelDependency(
            component.variantCompressionService()
        )
        kotlinProjectDataExtractor = DefaultKotlinProjectDataExtractor(
            dependenciesDataSource = component.dependenciesDataSource().get(),
            dependencyGraphsService = component.dependencyGraphsService(),
            grazelExtension = extension,
            gradleDependencyToBazelDependency = gradleDependencyToBazelDependency
        )
        kotlinUnitTestDataExtractor = DefaultKotlinUnitTestDataExtractor(
            dependenciesDataSource = component.dependenciesDataSource().get(),
            dependencyGraphsService = component.dependencyGraphsService(),
            grazelExtension = extension,
            gradleDependencyToBazelDependency = gradleDependencyToBazelDependency,
            testSizeCalculator = TestSizeCalculator(extension)
        )
    }

    @Test
    fun `project extractor derives transitive maven tags from selected direct deps`() {
        val data = kotlinProjectDataExtractor.extract(kotlinProject)

        assertTrue("@maven//:com_example_main_root" in data.tags)
        assertTrue("@maven//:com_example_main_child" in data.tags)
        assertTrue("@self//kotlin-lib" in data.tags)
        assertTrue("@maven//:com_example_test_root" !in data.tags)
    }

    @Test
    fun `unit test extractor derives transitive maven tags from selected test deps`() {
        val data = kotlinUnitTestDataExtractor.extract(kotlinProject)

        assertTrue("@maven//:com_example_test_root" in data.tags)
        assertTrue("@maven//:com_example_test_child" in data.tags)
        assertTrue("@self//kotlin-lib-test" in data.tags)
    }
}
