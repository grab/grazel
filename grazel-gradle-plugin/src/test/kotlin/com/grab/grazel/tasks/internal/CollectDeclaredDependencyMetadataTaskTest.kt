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

import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.DeclaredProjectMetadataSource
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.util.ProgressReporter
import com.grab.grazel.util.fromJson
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.UntrackedTask
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectDeclaredDependencyMetadataTaskTest {

    @Test
    fun `single task reads evaluated model directly and is explicitly untracked`() {
        val taskGetterNames = CollectDeclaredDependencyMetadataTask::class.java.methods
            .mapTo(mutableSetOf()) { method -> method.name }

        assertFalse(
            "SINGLE_TASK reads evaluated Gradle/AGP model directly and must not claim cacheability.",
            CollectDeclaredDependencyMetadataTask::class.java.isAnnotationPresent(CacheableTask::class.java)
        )
        assertTrue(
            "SINGLE_TASK must be explicit about its untracked model-read boundary.",
            CollectDeclaredDependencyMetadataTask::class.java.isAnnotationPresent(UntrackedTask::class.java)
        )
        assertFalse(
            "Declared metadata must not cross the task boundary as a JSON string input.",
            "getDeclaredDependencyMetadataJson" in taskGetterNames
        )
        assertFalse(
            "Build-file glob inputs are an imprecise proxy for evaluated declared metadata.",
            "getDependencyDeclarationFiles" in taskGetterNames
        )
    }

    @Test
    fun `build-file tracing helper is removed`() {
        val companionMethods = CollectDeclaredDependencyMetadataTask.Companion::class.java.methods
            .mapTo(mutableSetOf()) { method -> method.name }

        assertFalse(
            "Declared metadata invalidation must be based on semantic task inputs, not build file globs.",
            "dependencyDeclarationFileTree" in companionMethods
        )
    }

    @Test
    fun `fanout project shard task has no build-file or json-string payload inputs`() {
        val shardTaskGetters = CollectProjectDeclaredDependencyMetadataTask::class.java.methods
            .mapTo(mutableSetOf()) { method -> method.name }

        assertFalse(
            "PROJECT_TASK_FANOUT shard tasks read evaluated Gradle/AGP model directly and must not claim cacheability.",
            CollectProjectDeclaredDependencyMetadataTask::class.java.isAnnotationPresent(CacheableTask::class.java)
        )
        assertTrue(
            "PROJECT_TASK_FANOUT shard tasks must be explicit about their untracked model-read boundary.",
            CollectProjectDeclaredDependencyMetadataTask::class.java.isAnnotationPresent(UntrackedTask::class.java)
        )
        assertFalse(
            "Project shard tasks must not receive metadata through JSON string task inputs.",
            "getDeclaredDependencyMetadataJson" in shardTaskGetters
        )
        assertFalse(
            "Project shard tasks must not use broad build file inputs.",
            "getDependencyDeclarationFiles" in shardTaskGetters
        )
        assertFalse(
            "Project shard tasks must not have any @InputFiles build-script proxy getters.",
            CollectProjectDeclaredDependencyMetadataTask::class.java.methods.any { method ->
                method.isAnnotationPresent(InputFiles::class.java)
            }
        )
    }

    @Test
    fun `fanout merge task is cacheable and file based`() {
        val mergeTaskGetters = MergeDeclaredDependencyMetadataTask::class.java.methods
            .mapTo(mutableSetOf()) { method -> method.name }

        assertTrue(
            "Fanout merge should be cacheable because it merges shard files deterministically.",
            MergeDeclaredDependencyMetadataTask::class.java.isAnnotationPresent(CacheableTask::class.java)
        )
        assertTrue("getDeclaredDependencyMetadataShards" in mergeTaskGetters)
        assertTrue("getDeclaredDependencyMetadata" in mergeTaskGetters)
    }

    @Test
    fun `fanout shard task is owned by the source project`() {
        val rootProject = ProjectBuilder.builder().withName("root").build()
        val sourceProject = ProjectBuilder.builder().withName("library").withParent(rootProject).build()
        val implementation = sourceProject.configurations.create("implementation")
        val metadataSource = DeclaredProjectMetadataSource(
            project = sourceProject,
            variants = listOf(variant(project = sourceProject, configuration = implementation))
        )

        val task = CollectProjectDeclaredDependencyMetadataTask.register(
            metadataSource = metadataSource
        ).get()

        assertEquals(
            ":library:collectProjectDeclaredDependencyMetadata",
            task.path
        )
        assertFalse(
            "Source-project fanout must not leave root-flat shard tasks in the default graph.",
            "collectLibraryDeclaredDependencyMetadata" in rootProject.tasks.names
        )
        assertTrue(
            "Shard output should be owned by the source project's build directory.",
            task.declaredDependencyMetadataShard.get().asFile
                .relativeTo(sourceProject.layout.buildDirectory.get().asFile)
                .path
                .startsWith("grazel/declared-dependency-metadata/")
        )
    }

    @Test
    fun `fanout shard input snapshots declared metadata after task registration`() {
        val rootProject = ProjectBuilder.builder().withName("root").build()
        val sourceProject = ProjectBuilder.builder().withName("library").withParent(rootProject).build()
        val implementation = sourceProject.configurations.create("implementation")
        val metadataSource = DeclaredProjectMetadataSource(
            project = sourceProject,
            variants = listOf(variant(project = sourceProject, configuration = implementation))
        )
        val task = CollectProjectDeclaredDependencyMetadataTask.register(
            metadataSource = metadataSource
        ).get()

        sourceProject.dependencies.add("implementation", "com.example:late-added:1.0")
        task.action()

        val metadata = fromJson<DeclaredDependencyMetadata>(task.declaredDependencyMetadataShard.get().asFile)
        assertEquals(
            setOf("com.example:late-added:1.0"),
            metadata.projects.getValue(":library")
                .variants
                .single()
                .declaredDependencies
        )
    }

    @Test
    fun `single task progress is emitted from caller thread while snapshots run in parallel`() {
        val rootProject = ProjectBuilder.builder().withName("root").build()
        val metadataSources = (1..4).map { index ->
            val sourceProject = ProjectBuilder.builder()
                .withName("library$index")
                .withParent(rootProject)
                .build()
            val implementation = sourceProject.configurations.create("implementation")
            sourceProject.dependencies.add("implementation", "com.example:library$index:1.0")
            DeclaredProjectMetadataSource(
                project = sourceProject,
                variants = listOf(variant(project = sourceProject, configuration = implementation))
            )
        }
        val callerThread = Thread.currentThread()
        val progressMessages = mutableListOf<String>()

        val metadata = collectDeclaredDependencyMetadata(
            metadataSources = metadataSources,
            maxWorkerCount = 4,
            reporter = ProgressReporter { message ->
                assertEquals(callerThread, Thread.currentThread())
                progressMessages += message
            }
        )

        assertEquals(
            setOf(":library1", ":library2", ":library3", ":library4"),
            metadata.projects.keys
        )
        assertEquals(4, progressMessages.size)
        assertEquals(
            listOf(1, 2, 3, 4),
            progressMessages.map { message ->
                Regex("""\((\d+)/4\)""").find(message)?.groupValues?.get(1)?.toInt()
            }
        )
        assertEquals(
            setOf(":library1", ":library2", ":library3", ":library4"),
            progressMessages.mapTo(mutableSetOf()) { message ->
                message.substringAfter("snapshotting ").substringBefore(" (")
            }
        )
    }

    private fun variant(
        project: Project,
        configuration: Configuration
    ): Variant<Any> {
        return object : Variant<Any> {
            override val name: String = "default"
            override val backingVariant: Any = Any()
            override val project: Project = project
            override val variantType: VariantType = VariantType.AndroidBuild
            override val extendsFrom: Set<String> = emptySet()
            override val variantConfigurations: Set<Configuration> = setOf(configuration)
            override val compileConfiguration: Set<Configuration> = emptySet()
            override val runtimeConfiguration: Set<Configuration> = emptySet()
            override val annotationProcessorConfiguration: Set<Configuration> = emptySet()
            override val kspConfiguration: Set<Configuration> = emptySet()
            override val kotlinCompilerPluginConfiguration: Set<Configuration> = emptySet()
        }
    }
}
