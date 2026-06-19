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
import org.gradle.api.tasks.Internal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputeWorkspaceDependenciesTaskTest {

    @Test
    fun `compute workspace dependencies task does not own variant enumeration services`() {
        val taskGetterNames = ComputeWorkspaceDependenciesTask::class.java.methods
            .mapTo(mutableSetOf()) { method -> method.name }

        assertFalse(
            "Variant enumeration belongs before computeWorkspaceDependencies; " +
                "the compute task should consume serialized metadata instead of MigrationChecker.",
            "getMigrationCheckerProvider" in taskGetterNames
        )
        assertFalse(
            "Variant enumeration belongs before computeWorkspaceDependencies; " +
                "the compute task should consume serialized metadata instead of VariantBuilder.",
            "getVariantBuilderProvider" in taskGetterNames
        )
    }

    @Test
    fun `compute workspace dependencies task does not expose legacy resolution path inputs`() {
        val taskGetterNames = ComputeWorkspaceDependenciesTask::class.java.methods
            .mapTo(mutableSetOf()) { method -> method.name }

        assertFalse(
            "Dependency resolution is always aggregated; computeWorkspaceDependencies should not " +
                "consume legacy per-variant dependency JSON inputs.",
            "getCompileDependenciesJsons" in taskGetterNames
        )
        assertFalse(
            "Dependency resolution is always aggregated; there should be no task-level opt-out " +
                "input that switches back to the legacy fanout.",
            "getAggregatedDependencyResolution" in taskGetterNames
        )
    }

    @Test
    fun `compute workspace dependencies task consumes workspace dependency root providers and metadata`() {
        val taskGetterNames = ComputeWorkspaceDependenciesTask::class.java.methods
            .mapTo(mutableSetOf()) { method -> method.name }
        val rootComponentsGetter = ComputeWorkspaceDependenciesTask::class.java
            .getMethod("getWorkspaceDependencyRootComponents")

        assertFalse(
            "Root metadata is now workspace dependency input metadata; old aggregated-root " +
                "metadata naming should not be exposed by the compute task.",
            "getAggregatedDependencyRootMetadataJsons" in taskGetterNames
        )
        assertFalse(
            "A separate fingerprint proxy should not be needed for the master-like direct " +
                "root-provider path.",
            "getAggregatedDependencyRootFingerprints" in taskGetterNames
        )
        assertFalse(
            "The compute task should not expose the bespoke serialized root snapshot input.",
            "getAggregatedDependencyRootSnapshots" in taskGetterNames
        )
        assertTrue(
            "Workspace dependency root providers should be wired into computeWorkspaceDependencies " +
                "master-style.",
            "getWorkspaceDependencyRootComponents" in taskGetterNames
        )
        assertTrue(
            "Resolved root providers are safe cache inputs, matching the historical cacheable " +
                "ResolveVariantDependenciesTask shape.",
            rootComponentsGetter.isAnnotationPresent(Input::class.java)
        )
        assertFalse(
            "Resolved root providers should participate in the compute task cache key.",
            rootComponentsGetter.isAnnotationPresent(Internal::class.java)
        )
        assertTrue(
            "Stable root metadata should remain an explicit task input alongside root providers.",
            "getWorkspaceDependencyRootMetadataJsons" in taskGetterNames
        )
    }
}
