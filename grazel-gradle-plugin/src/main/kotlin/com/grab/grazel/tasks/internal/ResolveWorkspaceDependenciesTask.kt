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
import com.grab.grazel.gradle.dependencies.AggregatedDependencyRoot
import com.grab.grazel.gradle.dependencies.AggregatedDependencyRootMetadata
import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.util.fromJson
import com.grab.grazel.util.logHeap
import com.grab.grazel.util.writeJson
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

@CacheableTask
internal abstract class ResolveWorkspaceDependenciesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val declaredDependencyMetadata: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kspDependencies: RegularFileProperty

    @get:Input
    abstract val workspaceDependencyRootComponents: ListProperty<ResolvedComponentResult>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val workspaceDependencyRootMetadata: RegularFileProperty

    @get:OutputFile
    abstract val workspaceDependencyResults: RegularFileProperty

    init {
        group = GRAZEL_TASK_GROUP
        description = "Resolves aggregated Gradle dependency roots for workspace dependency computation"
    }

    @TaskAction
    fun action() {
        logger.logHeap("ResolveWorkspaceDeps:start")
        val rootComponents = workspaceDependencyRootComponents.get()
        val rootMetadata = fromJson<List<AggregatedDependencyRootMetadata>>(
            workspaceDependencyRootMetadata.get()
        )
        check(rootComponents.size == rootMetadata.size) {
            "Workspace dependency root component count (${rootComponents.size}) does not match " +
                "metadata count (${rootMetadata.size})"
        }

        val resolver = AggregatedDependencyResolver(
            logger = logger,
            declaredDependencyMetadata = fromJson<DeclaredDependencyMetadata>(
                declaredDependencyMetadata.get()
            ),
            precomputedKspDependencies = fromJson<ResolveDependenciesResult>(
                kspDependencies.get()
            ).dependencies.getOrDefault(KSP.name, emptySet()),
            workspaceDependencyRoots = rootComponents.zip(rootMetadata)
                .map { (root, metadata) -> AggregatedDependencyRoot(root, metadata) }
        )
        val results = resolver.resolve()

        logger.logHeap("ResolveWorkspaceDeps:resolved")
        workspaceDependencyResults.get().asFile.parentFile.mkdirs()
        writeJson(results, workspaceDependencyResults.get())
        logger.logHeap("ResolveWorkspaceDeps:done")
    }

    companion object {
        private const val TASK_NAME = "resolveWorkspaceDependencies"

        internal fun register(rootProject: Project): TaskProvider<ResolveWorkspaceDependenciesTask> {
            return rootProject.tasks.register<ResolveWorkspaceDependenciesTask>(TASK_NAME) {
                workspaceDependencyResults.set(
                    rootProject.layout.buildDirectory.file("grazel/workspace-dependency-results.json")
                )
                workspaceDependencyRootComponents.convention(emptyList())
            }
        }
    }
}
