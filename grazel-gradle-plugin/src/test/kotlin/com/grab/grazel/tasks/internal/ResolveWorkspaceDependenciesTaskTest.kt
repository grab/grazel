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

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.CacheableTask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        val metadataGetter = ResolveWorkspaceDependenciesTask::class.java
            .getMethod("getWorkspaceDependencyRootMetadataJsons")
        val resultsGetter = ResolveWorkspaceDependenciesTask::class.java
            .getMethod("getWorkspaceDependencyResults")

        assertTrue("getDeclaredDependencyMetadata" in taskGetterNames)
        assertTrue("getKspDependencies" in taskGetterNames)
        assertTrue("getWorkspaceDependencyRootComponents" in taskGetterNames)
        assertTrue("getWorkspaceDependencyRootMetadataJsons" in taskGetterNames)
        assertTrue("getWorkspaceDependencyResults" in taskGetterNames)

        assertTrue(rootComponentsGetter.isAnnotationPresent(Input::class.java))
        assertFalse(rootComponentsGetter.isAnnotationPresent(Internal::class.java))

        assertTrue(metadataGetter.isAnnotationPresent(Input::class.java))
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
    fun `workspace dependency input registrar does not own variant configuration semantics`() {
        val source = File(
            "src/main/kotlin/com/grab/grazel/tasks/internal/WorkspaceDependencyInputsRegistrar.kt"
        ).readText()

        assertFalse(source.contains("UnitTestRuntimeClasspath"))
        assertFalse(source.contains("UnitTestCompileClasspath"))
        assertFalse(source.contains("AndroidTestRuntimeClasspath"))
        assertFalse(source.contains("AndroidTestCompileClasspath"))
        assertFalse(source.contains("findByName(\"lintChecks\")"))
        assertFalse(source.contains("import com.android.build.gradle.api.BaseVariant"))
        assertFalse(source.contains("import com.android.builder.model.BuildType"))
    }
}
