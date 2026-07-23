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

import com.grab.grazel.fake.fakeComponentResult
import com.grab.grazel.gradle.dependencies.AggregatedDependencyRootKind
import com.grab.grazel.gradle.dependencies.AggregatedDependencyRootMetadata
import com.grab.grazel.gradle.dependencies.RootKey
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveWorkspaceDependenciesTaskTest {

    @Test
    fun `resolver task is cacheable like the old variant resolver`() {
        assertTrue(ResolveWorkspaceDependenciesTask::class.java.isAnnotationPresent(CacheableTask::class.java))
    }

    @Test
    fun `resolver task owns live Gradle roots and writes serialized results`() {
        val taskGetterNames = ResolveWorkspaceDependenciesTask::class.java.methods
            .mapTo(mutableSetOf()) { method -> method.name }
        val rootComponentsGetter = ResolveWorkspaceDependenciesTask::class.java
            .getMethod("getWorkspaceDependencyRootComponents")
        val rootKeysGetter = ResolveWorkspaceDependenciesTask::class.java
            .getMethod("getWorkspaceDependencyRootKeys")
        val metadataGetter = ResolveWorkspaceDependenciesTask::class.java
            .getMethod("getWorkspaceDependencyRootMetadata")
        val resultsGetter = ResolveWorkspaceDependenciesTask::class.java
            .getMethod("getWorkspaceDependencyResults")

        assertTrue("getDeclaredDependencyMetadata" in taskGetterNames)
        assertTrue("getKspDependencies" in taskGetterNames)
        assertTrue("getWorkspaceDependencyRootComponents" in taskGetterNames)
        assertTrue("getWorkspaceDependencyRootKeys" in taskGetterNames)
        assertTrue("getWorkspaceDependencyRootMetadata" in taskGetterNames)
        assertTrue("getWorkspaceDependencyResults" in taskGetterNames)

        assertTrue(rootComponentsGetter.isAnnotationPresent(Input::class.java))
        assertFalse(rootComponentsGetter.isAnnotationPresent(Internal::class.java))

        assertTrue(rootKeysGetter.isAnnotationPresent(Input::class.java))
        assertFalse(rootKeysGetter.isAnnotationPresent(Internal::class.java))

        assertTrue(metadataGetter.isAnnotationPresent(InputFile::class.java))
        assertTrue(metadataGetter.isAnnotationPresent(PathSensitive::class.java))
        assertTrue(resultsGetter.isAnnotationPresent(OutputFile::class.java))
    }

    @Test
    fun `resolver task consumes stable metadata files`() {
        val declaredMetadataGetter = ResolveWorkspaceDependenciesTask::class.java
            .getMethod("getDeclaredDependencyMetadata")
        val kspGetter = ResolveWorkspaceDependenciesTask::class.java
            .getMethod("getKspDependencies")

        assertTrue(declaredMetadataGetter.isAnnotationPresent(InputFile::class.java))
        assertTrue(declaredMetadataGetter.isAnnotationPresent(PathSensitive::class.java))
        assertTrue(kspGetter.isAnnotationPresent(InputFile::class.java))
        assertTrue(kspGetter.isAnnotationPresent(PathSensitive::class.java))
    }

    @Test
    fun `root metadata writer task owns serialized metadata file output`() {
        val metadataGetter = CollectWorkspaceDependencyRootMetadataTask::class.java
            .getMethod("getWorkspaceDependencyRootMetadata")

        assertTrue(
            CollectWorkspaceDependencyRootMetadataTask::class.java.isAnnotationPresent(
                CacheableTask::class.java
            )
        )
        assertTrue(metadataGetter.isAnnotationPresent(OutputFile::class.java))
    }

    @Test
    fun `pairRootsByKey joins index-aligned keys and components to metadata sharing the same key`() {
        val key = RootKey(
            projectPath = ":app",
            configurationName = "debugRuntimeClasspath",
            kind = AggregatedDependencyRootKind.MAIN_HIERARCHY
        )
        val component = fakeComponentResult(projectPath = ":app")
        val metadata = AggregatedDependencyRootMetadata(
            projectPath = key.projectPath,
            kind = key.kind,
            configurationName = key.configurationName
        )

        val roots = pairRootsByKey(
            rootKeys = listOf(key),
            rootComponents = listOf(component),
            rootMetadata = listOf(metadata)
        )

        assertEquals(1, roots.size)
        assertEquals(component, roots.single().root)
        assertEquals(metadata, roots.single().metadata)
    }

    @Test
    fun `pairRootsByKey fails loudly with the missing key when no metadata matches`() {
        val presentKey = RootKey(
            projectPath = ":app",
            configurationName = "debugRuntimeClasspath",
            kind = AggregatedDependencyRootKind.MAIN_HIERARCHY
        )
        val missingKey = RootKey(
            projectPath = ":app",
            configurationName = "releaseRuntimeClasspath",
            kind = AggregatedDependencyRootKind.MAIN_HIERARCHY
        )
        val metadata = AggregatedDependencyRootMetadata(
            projectPath = presentKey.projectPath,
            kind = presentKey.kind,
            configurationName = presentKey.configurationName
        )

        val exception = assertThrows(IllegalStateException::class.java) {
            pairRootsByKey(
                rootKeys = listOf(missingKey),
                rootComponents = listOf(fakeComponentResult(projectPath = ":app")),
                rootMetadata = listOf(metadata)
            )
        }

        assertTrue(exception.message.orEmpty().contains(missingKey.toString()))
    }

    @Test
    fun `pairRootsByKey fails loudly when keys and components drift out of index alignment`() {
        val key = RootKey(
            projectPath = ":app",
            configurationName = "debugRuntimeClasspath",
            kind = AggregatedDependencyRootKind.MAIN_HIERARCHY
        )
        val metadata = AggregatedDependencyRootMetadata(
            projectPath = key.projectPath,
            kind = key.kind,
            configurationName = key.configurationName
        )

        val exception = assertThrows(IllegalStateException::class.java) {
            pairRootsByKey(
                rootKeys = listOf(key),
                rootComponents = emptyList(),
                rootMetadata = listOf(metadata)
            )
        }

        assertTrue(exception.message.orEmpty().contains("does not match"))
    }
}
