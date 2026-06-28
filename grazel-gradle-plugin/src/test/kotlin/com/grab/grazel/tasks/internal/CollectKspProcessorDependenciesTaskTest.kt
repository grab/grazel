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

import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CollectKspProcessorDependenciesTaskTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `ksp processor dependency task declares resolved roots and artifact mapping as inputs`() {
        val taskGetterNames = CollectKspProcessorDependenciesTask::class.java.methods
            .mapTo(mutableSetOf()) { method -> method.name }
        val rootComponentsGetter = CollectKspProcessorDependenciesTask::class.java
            .getMethod("getKspRootComponents")
        val directDependenciesGetter = CollectKspProcessorDependenciesTask::class.java
            .getMethod("getKspDirectDependencies")
        val artifactMappingGetter = CollectKspProcessorDependenciesTask::class.java
            .getMethod("getKspArtifactMapping")
        val classpathFilesGetter = CollectKspProcessorDependenciesTask::class.java
            .getMethod("getKspClasspathFiles")

        assertFalse(
            "KSP sidecar resolution should be driven by explicit root-component inputs, not hidden " +
                "Configuration handles.",
            "getKspConfigurations" in taskGetterNames
        )
        assertFalse(
            "KSP root components should not be hidden from Gradle caching.",
            rootComponentsGetter.isAnnotationPresent(Internal::class.java)
        )
        assertTrue(
            "KSP root components should participate in the task cache key.",
            rootComponentsGetter.isAnnotationPresent(Input::class.java)
        )
        assertEquals(
            "KSP direct dependency input only needs direct short IDs.",
            SetProperty::class.java,
            directDependenciesGetter.returnType
        )
        assertTrue(
            "KSP artifact mapping should participate in the task cache key.",
            artifactMappingGetter.isAnnotationPresent(Input::class.java)
        )
        assertFalse(
            "KSP artifact mapping should not be hidden from Gradle caching.",
            artifactMappingGetter.isAnnotationPresent(Internal::class.java)
        )
        assertEquals(
            "KSP classpath paths must remain distinct so same-basename processor jars do not collide.",
            PathSensitivity.ABSOLUTE,
            classpathFilesGetter.getAnnotation(PathSensitive::class.java).value
        )
    }

    @Test
    fun `ksp processor dependency task creates output parent directory`() {
        val project = ProjectBuilder.builder()
            .withProjectDir(temporaryFolder.newFolder("project"))
            .build()
        val task = project.tasks
            .register("collectKspProcessorDependencies", CollectKspProcessorDependenciesTask::class.java)
            .get()
        val outputFile = project.layout.buildDirectory.file("nested/output/ksp-dependencies.json")

        task.kspRootComponents.set(emptyList())
        task.kspDirectDependencies.set(emptySet())
        task.kspArtifactMapping.set(emptyMap())
        task.kspDependencies.set(outputFile)

        task.action()

        assertTrue(outputFile.get().asFile.exists())
    }
}
