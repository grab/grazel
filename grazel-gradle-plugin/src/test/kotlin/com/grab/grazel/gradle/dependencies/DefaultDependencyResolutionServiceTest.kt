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
import com.grab.grazel.gradle.dependencies.model.OverrideTarget
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
import org.junit.Assert.assertNull
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
    fun `test getMavenDependency prefers exact version before broad fallback`() {
        // Given
        val defaultDependency = ResolvedDependency.fromId(
            "com.example:library:1.0",
            "default"
        )
        val testDependency = ResolvedDependency.fromId(
            "com.example:library:2.0",
            "test"
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                "default" to listOf(defaultDependency),
                "test" to listOf(testDependency)
            )
        )
        writeWorkspaceDependenciesToFile(workspaceDependencies)
        dependencyResolutionService.init(workspaceDependenciesFile)

        // When
        val testVersionDep = dependencyResolutionService.getMavenDependency(
            setOf("default", "test"),
            "com.example",
            "library",
            version = "2.0"
        )
        val defaultVersionDep = dependencyResolutionService.getMavenDependency(
            setOf("default", "test"),
            "com.example",
            "library",
            version = "1.0"
        )
        val broadDep = dependencyResolutionService.getMavenDependency(
            setOf("default", "test"),
            "com.example",
            "library"
        )

        // Then
        assertNotNull(testVersionDep)
        assertEquals("test_maven", testVersionDep?.repo)
        assertNotNull(defaultVersionDep)
        assertEquals("maven", defaultVersionDep?.repo)
        assertNotNull(broadDep)
        assertEquals("maven", broadDep?.repo)
    }

    @Test
    fun `test getMavenDependency falls back to indexed resolved version when exact declared version lost conflict`() {
        // Given
        val resolvedDependency = ResolvedDependency.fromId(
            "androidx.appcompat:appcompat:1.2.0",
            "default"
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf("default" to listOf(resolvedDependency))
        )
        writeWorkspaceDependenciesToFile(workspaceDependencies)
        dependencyResolutionService.init(workspaceDependenciesFile)

        // When
        val mavenDep = dependencyResolutionService.getMavenDependency(
            setOf("default"),
            "androidx.appcompat",
            "appcompat",
            version = "1.1.0"
        )

        // Then
        assertEquals("maven", mavenDep?.repo)
        assertEquals("androidx.appcompat", mavenDep?.group)
        assertEquals("appcompat", mavenDep?.name)
    }

    @Test
    fun `test getMavenDependency returns override target instead of leaf repo for duplicate`() {
        // Given
        val commonDependency = ResolvedDependency.fromId(
            "androidx.activity:activity:1.0",
            "default"
        )
        val leafDuplicate = commonDependency.copy(
            repository = "demoFreeDebug",
            overrideTarget = OverrideTarget(
                artifactShortId = "androidx.activity:activity",
                label = MavenDependency(
                    repo = "maven",
                    group = "androidx.activity",
                    name = "activity"
                )
            )
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                "default" to listOf(commonDependency),
                "demoFreeDebug" to listOf(leafDuplicate)
            )
        )
        writeWorkspaceDependenciesToFile(workspaceDependencies)
        dependencyResolutionService.init(workspaceDependenciesFile)

        // When
        val mavenDep = dependencyResolutionService.getMavenDependency(
            setOf("demoFreeDebug", "debug", "free", "default"),
            "androidx.activity",
            "activity"
        )

        // Then
        assertNotNull(mavenDep)
        assertEquals("maven", mavenDep?.repo)
        assertEquals("androidx.activity", mavenDep?.group)
        assertEquals("activity", mavenDep?.name)
    }

    @Test
    fun `test getMavenDependency prefers nearest exact topology bucket over default duplicate`() {
        // Given
        val defaultDependency = ResolvedDependency.fromId(
            "androidx.constraintlayout:constraintlayout:2.0.1",
            "default"
        )
        val requestedTopologyDependency = ResolvedDependency.fromId(
            "androidx.constraintlayout:constraintlayout:2.0.1",
            "flavor2Debug"
        )
        val requestedTopologyOnlyDependency = ResolvedDependency.fromId(
            "javax.annotation:javax.annotation-api:1.3.2",
            "flavor2Debug"
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                "default" to listOf(defaultDependency),
                "flavor2Debug" to listOf(
                    requestedTopologyDependency,
                    requestedTopologyOnlyDependency
                )
            )
        )
        writeWorkspaceDependenciesToFile(workspaceDependencies)
        dependencyResolutionService.init(workspaceDependenciesFile)

        // When
        val inheritedDep = dependencyResolutionService.getMavenDependency(
            setOf("flavor2Debug", "paidDebug", "debug", "default", "paid"),
            "androidx.constraintlayout",
            "constraintlayout",
            version = "2.0.1"
        )
        val topologyOnlyDep = dependencyResolutionService.getMavenDependency(
            setOf("flavor2Debug", "paidDebug", "debug", "default", "paid"),
            "javax.annotation",
            "javax.annotation-api",
            version = "1.3.2"
        )

        // Then
        assertEquals("flavor2debug_maven", inheritedDep?.repo)
        assertEquals("flavor2debug_maven", topologyOnlyDep?.repo)
    }

    @Test
    fun `test transitive child dependency does not shadow direct default dependency`() {
        // Given
        val defaultDependency = ResolvedDependency.fromId(
            "androidx.core:core:1.13.1",
            "default"
        )
        val debugTransitiveDependency = ResolvedDependency.fromId(
            "androidx.core:core:1.3.2",
            "debug"
        ).copy(direct = false)
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                "default" to listOf(defaultDependency),
                "debug" to listOf(debugTransitiveDependency)
            )
        )
        writeWorkspaceDependenciesToFile(workspaceDependencies)
        dependencyResolutionService.init(workspaceDependenciesFile)

        // When
        val mavenDep = dependencyResolutionService.getMavenDependency(
            setOf("debug", "default"),
            "androidx.core",
            "core"
        )

        // Then
        assertNotNull(mavenDep)
        assertEquals("maven", mavenDep?.repo)
        assertEquals("androidx.core", mavenDep?.group)
        assertEquals("core", mavenDep?.name)
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

        // Then - Missing deps must not invent @maven labels that may not exist after pinning.
        assertNull(mavenDep)
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
    fun `test empty variants set returns null when default bucket does not contain dependency`() {
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

        // Then
        assertNull(mavenDep)
    }

    @Test
    fun `test empty variants set falls back to indexed default bucket`() {
        // Given
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                "default" to listOf(
                    ResolvedDependency.fromId("com.example:library1:1.0", "default")
                )
            )
        )
        writeWorkspaceDependenciesToFile(workspaceDependencies)
        dependencyResolutionService.init(workspaceDependenciesFile)

        // When
        val mavenDep = dependencyResolutionService.getMavenDependency(
            emptySet(),
            "com.example",
            "library1"
        )

        // Then
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
            variantDeps = mapOf(
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
            variantDeps = mapOf(
                "variant1" to variant1Dependencies
            ),
            transitiveClasspath = transitiveClasspath
        )
    }

    private fun writeWorkspaceDependenciesToFile(workspaceDependencies: WorkspaceDependencies) {
        workspaceDependenciesFile.writeText(Json.encodeToString(workspaceDependencies))
    }
}
