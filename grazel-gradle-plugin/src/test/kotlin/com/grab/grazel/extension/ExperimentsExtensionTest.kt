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

package com.grab.grazel.extension

import com.grab.grazel.extension.DeclaredDependencyMetadataAggregationMode.PROJECT_TASK_FANOUT
import com.grab.grazel.extension.DeclaredDependencyMetadataAggregationMode.SINGLE_TASK
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentsExtensionTest {
    @Test
    fun `declared dependency metadata aggregation mode defaults to project task fanout`() {
        val project = ProjectBuilder.builder().build()
        val extension = ExperimentsExtension(project.objects)

        assertEquals(PROJECT_TASK_FANOUT, extension.declaredDependencyMetadataAggregationMode.get())
    }

    @Test
    fun `declared dependency metadata aggregation mode can be switched to project task fanout`() {
        val project = ProjectBuilder.builder().build()
        val extension = ExperimentsExtension(project.objects)

        extension.declaredDependencyMetadataAggregationMode.set(PROJECT_TASK_FANOUT)

        assertEquals(PROJECT_TASK_FANOUT, extension.declaredDependencyMetadataAggregationMode.get())
    }

    @Test
    fun `declared dependency metadata aggregation mode can be switched to single task`() {
        val project = ProjectBuilder.builder().build()
        val extension = ExperimentsExtension(project.objects)

        extension.declaredDependencyMetadataAggregationMode.set(SINGLE_TASK)

        assertEquals(SINGLE_TASK, extension.declaredDependencyMetadataAggregationMode.get())
    }

    @Test
    fun `local maven resolution defaults off and can be enabled`() {
        val project = ProjectBuilder.builder().build()
        val extension = ExperimentsExtension(project.objects)

        assertFalse(extension.localMavenResolution.get())

        extension.localMavenResolution.set(true)

        assertTrue(extension.localMavenResolution.get())
    }

    @Test
    fun `limit dependency resolution parallelism remains as a compatibility no-op`() {
        val project = ProjectBuilder.builder().build()
        val extension = ExperimentsExtension(project.objects)

        assertFalse(extension.limitDependencyResolutionParallelism.get())

        extension.limitDependencyResolutionParallelism.set(true)

        assertTrue(extension.limitDependencyResolutionParallelism.get())
    }
}
