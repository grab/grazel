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
    fun `compute workspace dependencies task consumes serialized aggregated root snapshots`() {
        val taskGetterNames = ComputeWorkspaceDependenciesTask::class.java.methods
            .mapTo(mutableSetOf()) { method -> method.name }

        assertFalse(
            "The cacheable task action should not consume live Gradle ResolvedComponentResult " +
                "handles; provider mapping should serialize them before task execution.",
            "getAggregatedDependencyRoots" in taskGetterNames
        )
        assertFalse(
            "Separate root metadata inputs should be folded into the serialized snapshot so the " +
                "cache key and resolver input cannot drift apart.",
            "getAggregatedDependencyRootMetadataJsons" in taskGetterNames
        )
        assertFalse(
            "A separate fingerprint proxy should not be needed once the task consumes the full " +
                "serialized root snapshot.",
            "getAggregatedDependencyRootFingerprints" in taskGetterNames
        )
        assertTrue(
            "Aggregated classpath resolution should be wired into computeWorkspaceDependencies " +
                "as serialized snapshots, not looked up from live Configuration objects.",
            "getAggregatedDependencyRootSnapshots" in taskGetterNames
        )
    }
}
