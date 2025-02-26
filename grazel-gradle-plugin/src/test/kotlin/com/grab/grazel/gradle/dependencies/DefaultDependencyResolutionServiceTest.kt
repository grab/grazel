/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
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
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DefaultDependencyResolutionServiceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var dependencyResolutionService: DefaultDependencyResolutionService
    private lateinit var workspaceDependenciesFile: File

    @Before
    fun setup() {
        dependencyResolutionService = object : DefaultDependencyResolutionService() {
            override fun getParameters(): DependencyResolutionService.Params {
                TODO("Not yet implemented")
            }
        }
        workspaceDependenciesFile = temporaryFolder.newFile("workspace-dependencies.json")
    }

    @Test
    fun `test init populates cache correctly`() {
        // Given
        val workspaceDependencies = createSampleWorkspaceDependencies()
        writeWorkspaceDependenciesToFile(workspaceDependencies)

        // When
        val result = dependencyResolutionService.init(workspaceDependenciesFile)

        // Then
        assertEquals(workspaceDependencies, result)

        // Verify maven dependency can be retrieved
        val mavenDep = dependencyResolutionService.getMavenDependency(
            setOf("variant1"),
            "com.example",
            "library1"
        )
        assertNotNull(mavenDep)
        assertEquals("variant1_maven", mavenDep?.repo)
        assertEquals("com.example", mavenDep?.group)
        assertEquals("library1", mavenDep?.name)

        // Verify transitive dependencies can be retrieved
        val transitiveDeps =
            dependencyResolutionService.getTransitiveDependencies("com.example:library1")
        assertEquals(2, transitiveDeps.size)
        assertTrue(transitiveDeps.contains("com.example:dependency1"))
        assertTrue(transitiveDeps.contains("com.example:dependency2"))
    }

    @Test
    fun `test getMavenDependency returns dependency from correct variant`() {
        // Given
        val workspaceDependencies = createSampleWorkspaceDependencies()
        writeWorkspaceDependenciesToFile(workspaceDependencies)
        dependencyResolutionService.init(workspaceDependenciesFile)

        // When - Test variant priority
        val mavenDep = dependencyResolutionService.getMavenDependency(
            setOf("variant2", "variant1"),
            "com.example",
            "library1"
        )

        // Then - Should return from variant2 since it's first in the list
        assertNotNull(mavenDep)
        assertEquals("variant2_maven", mavenDep?.repo)
        assertEquals("com.example", mavenDep?.group)
        assertEquals("library1", mavenDep?.name)
    }

    @Test
    fun `test getMavenDependency returns null when dependency not found in any variant`() {
        // Given
        val workspaceDependencies = createSampleWorkspaceDependencies()
        writeWorkspaceDependenciesToFile(workspaceDependencies)
        dependencyResolutionService.init(workspaceDependenciesFile)

        // When - Test with non-existent dependency
        val mavenDep = dependencyResolutionService.getMavenDependency(
            setOf("variant1", "variant2"),
            "com.nonexistent",
            "library"
        )

        // Then - Should return a default MavenDependency with "maven" repo
        assertNotNull(mavenDep)
        assertEquals("maven", mavenDep?.repo)
        assertEquals("com.nonexistent", mavenDep?.group)
        assertEquals("library", mavenDep?.name)
    }

    @Test
    fun `test getTransitiveDependencies returns empty set when dependency not found`() {
        // Given
        val workspaceDependencies = createSampleWorkspaceDependencies()
        writeWorkspaceDependenciesToFile(workspaceDependencies)
        dependencyResolutionService.init(workspaceDependenciesFile)

        // When
        val transitiveDeps =
            dependencyResolutionService.getTransitiveDependencies("com.nonexistent:library")

        // Then
        assertTrue(transitiveDeps.isEmpty())
    }

    @Test
    fun `test empty variants set falls back to default maven repo`() {
        // Given
        val workspaceDependencies = createSampleWorkspaceDependencies()
        writeWorkspaceDependenciesToFile(workspaceDependencies)
        dependencyResolutionService.init(workspaceDependenciesFile)

        // When - Call with empty variants set
        val mavenDep = dependencyResolutionService.getMavenDependency(
            emptySet(),
            "com.example",
            "library1"
        )

        // Then - Should fall back to default maven repo
        assertNotNull(mavenDep)
        assertEquals("maven", mavenDep?.repo)
        assertEquals("com.example", mavenDep?.group)
        assertEquals("library1", mavenDep?.name)
    }

    @Test
    fun `test concurrent initialization`() {
        // Given
        val workspaceDependencies = createSampleWorkspaceDependencies()
        writeWorkspaceDependenciesToFile(workspaceDependencies)

        // When - Initialize from multiple threads
        runBlocking {
            val tasks = List(5) {
                async(Dispatchers.IO) {
                    dependencyResolutionService.init(workspaceDependenciesFile)
                }
            }

            // Wait for all initializations to complete
            val results = tasks.awaitAll()

            // Then - All results should be the same
            results.forEach { result ->
                assertEquals(workspaceDependencies, result)
            }
        }

        // Verify the initialization was successful
        val mavenDep = dependencyResolutionService.getMavenDependency(
            setOf("variant1"),
            "com.example",
            "library1"
        )
        assertNotNull(mavenDep)
    }

    @Test
    fun `test concurrent getMavenDependency calls`() {
        // Given
        val workspaceDependencies = createSampleWorkspaceDependencies()
        writeWorkspaceDependenciesToFile(workspaceDependencies)
        dependencyResolutionService.init(workspaceDependenciesFile)

        // When - Call getMavenDependency from multiple threads
        val executor = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(1)
        val results = mutableListOf<MavenDependency?>()

        repeat(20) {
            executor.submit {
                try {
                    latch.await() // Wait for all threads to be ready
                    val result = dependencyResolutionService.getMavenDependency(
                        setOf("variant1"),
                        "com.example",
                        "library1"
                    )
                    synchronized(results) {
                        results.add(result)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        latch.countDown() // Release all threads at once
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        // Then - All results should be the same
        assertEquals(20, results.size)
        results.forEach { result ->
            assertNotNull(result)
            assertEquals("variant1_maven", result?.repo)
            assertEquals("com.example", result?.group)
            assertEquals("library1", result?.name)
        }
    }

    private fun createSampleWorkspaceDependencies(): WorkspaceDependencies {
        val variant1Dependencies = listOf(
            ResolvedDependency.fromId("com.example:library1:1.0", "variant1"),
            ResolvedDependency.fromId("com.example:library2:1.0", "variant1")
        )

        val variant2Dependencies = listOf(
            ResolvedDependency.fromId("com.example:library1:2.0", "variant2"),
            ResolvedDependency.fromId("com.example:library3:1.0", "variant2")
        )

        val transitiveClasspath = mapOf(
            "com.example:library1" to setOf(
                "com.example:dependency1",
                "com.example:dependency2"
            ),
            "com.example:library2" to setOf(
                "com.example:dependency3"
            )
        )

        return WorkspaceDependencies(
            result = mapOf(
                "variant1" to variant1Dependencies,
                "variant2" to variant2Dependencies
            ),
            transitiveClasspath = transitiveClasspath
        )
    }

    private fun createDifferentWorkspaceDependencies(): WorkspaceDependencies {
        val variant1Dependencies = listOf(
            ResolvedDependency.fromId("com.different:library1:1.0", "variant1")
        )

        val transitiveClasspath = mapOf(
            "com.different:library1" to setOf(
                "com.different:dependency1"
            )
        )

        return WorkspaceDependencies(
            result = mapOf(
                "variant1" to variant1Dependencies
            ),
            transitiveClasspath = transitiveClasspath
        )
    }

    private fun writeWorkspaceDependenciesToFile(workspaceDependencies: WorkspaceDependencies) {
        workspaceDependenciesFile.writeText(Json.encodeToString(workspaceDependencies))
    }
} 