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

import com.grab.grazel.gradle.dependencies.AggregatedDependencyResolver
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputeWorkspaceDependenciesTaskTest {

    @Test
    fun `compute workspace dependencies registration does not own orchestration services`() {
        val registerSignatures = ComputeWorkspaceDependenciesTask.Companion::class.java.methods
            .filter { method -> method.name == "register" }
            .joinToString(separator = "\n") { method ->
                method.genericParameterTypes.joinToString()
            }

        assertFalse(
            "Variant enumeration belongs in TasksManager and WorkspaceDependencyInputsRegistrar, " +
                "not in ComputeWorkspaceDependenciesTask.register.",
            "VariantBuilder" in registerSignatures
        )
        assertFalse(
            "Migration filtering belongs in TasksManager and WorkspaceDependencyInputsRegistrar, " +
                "not in ComputeWorkspaceDependenciesTask.register.",
            "MigrationChecker" in registerSignatures
        )
        assertFalse(
            "Dependency graph service setup belongs in TasksManager, not in " +
                "ComputeWorkspaceDependenciesTask.register.",
            "DefaultDependencyGraphsService" in registerSignatures
        )
    }

    @Test
    fun `aggregated dependency resolver does not receive Gradle project model`() {
        val receivesProject = AggregatedDependencyResolver::class.java.declaredConstructors
            .any { constructor ->
                constructor.parameterTypes.any { parameterType ->
                    parameterType.name == "org.gradle.api.Project"
                }
            }

        assertFalse(
            "Workspace dependency computation should receive explicit task inputs; " +
                "AggregatedDependencyResolver should not receive a Project handle.",
            receivesProject
        )
    }

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
    fun `compute workspace dependencies task consumes serialized resolver output`() {
        val taskGetterNames = ComputeWorkspaceDependenciesTask::class.java.methods
            .mapTo(mutableSetOf()) { method -> method.name }
        val workspaceDependencyResultsGetter = ComputeWorkspaceDependenciesTask::class.java
            .getMethod("getWorkspaceDependencyResults")

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
        assertFalse(
            "Live Gradle root providers belong to the resolver task, not cacheable " +
                "computeWorkspaceDependencies.",
            "getWorkspaceDependencyRootComponents" in taskGetterNames
        )
        assertTrue(
            "Stable resolver output should be the cacheable compute task input.",
            "getWorkspaceDependencyResults" in taskGetterNames
        )
        assertTrue(workspaceDependencyResultsGetter.isAnnotationPresent(InputFile::class.java))
        assertTrue(workspaceDependencyResultsGetter.isAnnotationPresent(PathSensitive::class.java))
        assertFalse(
            "Live root metadata should be consumed by the resolver task, not compute.",
            "getWorkspaceDependencyRootMetadataJsons" in taskGetterNames
        )
        assertFalse(
            "Workspace root metadata should be file-backed and consumed by the resolver task, " +
                "not compute.",
            "getWorkspaceDependencyRootMetadata" in taskGetterNames
        )
    }
}
