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

import com.grab.grazel.fake.fakeComponentResult
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.component.Artifact
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalMavenResolvedFactsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `artifact index uses maven relative path with original artifact file name`() {
        val jar = temporaryFolder.newFile("library-1.2.3-classifier.jar")
        val aar = temporaryFolder.newFile("android-4.5.6.aar")
        val index = ResolvedArtifactIndexBuilder.indexArtifacts(
            artifacts = listOf(
                fakeArtifact("com.example", "library", "1.2.3", jar),
                fakeArtifact("com.example.android", "android", "4.5.6", aar)
            )
        )

        assertEquals(
            mapOf(
                "com/example/library/1.2.3/library-1.2.3-classifier.jar" to jar,
                "com/example/android/android/4.5.6/android-4.5.6.aar" to aar
            ),
            index
        )
    }

    @Test
    fun `artifact index aliases non maven physical file name to canonical maven path`() {
        val aar = temporaryFolder.newFile("ui-release.aar")
        val index = ResolvedArtifactIndexBuilder.indexArtifacts(
            artifacts = listOf(
                fakeArtifact("androidx.compose.ui", "ui-android", "1.7.8", aar)
            )
        )

        assertEquals(
            mapOf(
                "androidx/compose/ui/ui-android/1.7.8/ui-android-1.7.8.aar" to aar,
                "androidx/compose/ui/ui-android/1.7.8/ui-release.aar" to aar
            ),
            index
        )
    }

    @Test
    fun `module cache index adds maven layout files for resolved components`() {
        val gradleHome = temporaryFolder.newFolder("gradle-home")
        val cacheDirectory = File(
            gradleHome,
            "caches/modules-2/files-2.1/androidx.compose.ui/ui-android/1.7.8/hash"
        ).apply { mkdirs() }
        val aar = cacheDirectory.resolve("ui-release.aar").apply { writeText("aar") }
        val module = cacheDirectory.resolve("ui-android-1.7.8.module").apply { writeText("module") }

        val index = GradleModuleCacheFileIndexBuilder(gradleHome)
            .index(listOf("androidx.compose.ui:ui-android:1.7.8"))

        assertEquals(
            mapOf(
                "androidx/compose/ui/ui-android/1.7.8/ui-android-1.7.8.aar" to aar,
                "androidx/compose/ui/ui-android/1.7.8/ui-android-1.7.8.module" to module,
                "androidx/compose/ui/ui-android/1.7.8/ui-release.aar" to aar
            ),
            index
        )
    }

    @Test
    fun `component index includes external components and skips project components`() {
        val external = fakeComponentResult(
            group = "com.example",
            name = "library",
            version = "1.2.3",
            isProject = false
        )
        val project = fakeComponentResult(
            group = "com.example",
            name = "project",
            version = "1.0",
            isProject = true,
            projectPath = ":project"
        )

        val index = ResolvedComponentIndexBuilder.indexComponents(listOf(project, external))

        assertEquals(mapOf("com.example:library:1.2.3" to external.id), index)
    }

    @Test
    fun `pom resolver memoizes known component query`() {
        val pom = temporaryFolder.newFile("library-1.2.3.pom")
        val component = fakeComponent("com.example", "library", "1.2.3")
        var queries = 0
        val resolver = GradlePomFileResolver(
            componentIdsByGav = mapOf("com.example:library:1.2.3" to component),
            queryPom = {
                queries += 1
                pom
            }
        )

        assertEquals(pom, resolver.resolvePom("com.example:library:1.2.3"))
        assertEquals(pom, resolver.resolvePom("com.example:library:1.2.3"))
        assertEquals(1, queries)
    }

    @Test
    fun `pom resolver returns null for unknown component`() {
        val resolver = GradlePomFileResolver(
            componentIdsByGav = emptyMap(),
            queryPom = { error("Unknown component should not query Gradle") }
        )

        assertNull(resolver.resolvePom("com.example:missing:1.0"))
    }

    @Test
    fun `pom resolver returns null when known component has no pom file`() {
        val component = fakeComponent("com.example", "broken", "1.0")
        val resolver = GradlePomFileResolver(
            componentIdsByGav = mapOf("com.example:broken:1.0" to component),
            queryPom = { null }
        )

        assertNull(resolver.resolvePom("com.example:broken:1.0"))
    }

    private fun fakeArtifact(
        group: String,
        module: String,
        version: String,
        file: File
    ): ResolvedArtifactResult {
        val component = fakeComponent(group, module, version)
        return object : ResolvedArtifactResult {
            override fun getFile(): File = file
            override fun getId(): ComponentArtifactIdentifier = object : ComponentArtifactIdentifier {
                override fun getComponentIdentifier(): ComponentIdentifier = component
                override fun getDisplayName(): String = file.name
            }

            override fun getType(): Class<out Artifact> = Artifact::class.java
            override fun getVariant(): ResolvedVariantResult = error("Variant is not used by this test")
        }
    }

    private fun fakeComponent(
        group: String,
        module: String,
        version: String
    ): ModuleComponentIdentifier {
        return object : ModuleComponentIdentifier {
            override fun getGroup(): String = group
            override fun getModule(): String = module
            override fun getVersion(): String = version
            override fun getDisplayName(): String = "$group:$module:$version"
            override fun getModuleIdentifier(): ModuleIdentifier = object : ModuleIdentifier {
                override fun getGroup(): String = group
                override fun getName(): String = module
            }
        }
    }
}
