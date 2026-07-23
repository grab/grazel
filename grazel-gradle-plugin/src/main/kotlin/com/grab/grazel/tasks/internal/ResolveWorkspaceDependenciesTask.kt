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
import com.grab.grazel.gradle.dependencies.RootKey
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.dependencies.rootKey
import com.grab.grazel.di.GradleServices
import com.grab.grazel.util.fromJson
import com.grab.grazel.util.logHeap
import com.grab.grazel.util.withProgress
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

    /**
     * Index-aligned with [workspaceDependencyRootComponents] BY CONSTRUCTION of the single loop
     * in [com.grab.grazel.tasks.internal.WorkspaceDependencyInputsRegistrar.register] that adds to
     * both properties per `rootInput` in the same iteration. Do not wire this property from any
     * other configure block — that alignment invariant is the whole point of keeping the two
     * lists this narrowly scoped. The two lists cannot be folded into one wrapper-element `@Input`
     * list — see [RootKey]'s KDoc for the fingerprinting constraint that forces the split.
     */
    @get:Input
    abstract val workspaceDependencyRootKeys: ListProperty<RootKey>

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
        val rootKeys = workspaceDependencyRootKeys.get()
        val rootComponents = workspaceDependencyRootComponents.get()
        val rootMetadata = fromJson<List<AggregatedDependencyRootMetadata>>(
            workspaceDependencyRootMetadata.get()
        )
        val workspaceDependencyRoots = pairRootsByKey(rootKeys, rootComponents, rootMetadata)

        val startedAt = System.nanoTime()
        val results = GradleServices.from(project).progressLoggerFactory.withProgress(
            "resolving workspace dependencies"
        ) { reporter ->
            AggregatedDependencyResolver(
                logger = logger,
                reporter = reporter,
                declaredDependencyMetadata = fromJson<DeclaredDependencyMetadata>(
                    declaredDependencyMetadata.get()
                ),
                precomputedKspDependencies = fromJson<ResolveDependenciesResult>(
                    kspDependencies.get()
                ).dependencies.getOrDefault(KSP.name, emptySet()),
                workspaceDependencyRoots = workspaceDependencyRoots
            ).resolve()
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        val dependencyCount = results.sumOf { result ->
            result.dependencies.values.sumOf { dependencies -> dependencies.size }
        }
        logger.quiet(
            "Resolved $dependencyCount deps across ${rootMetadata.size} roots in ${elapsedMs}ms"
        )

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
                workspaceDependencyRootKeys.convention(emptyList())
            }
        }
    }
}

/**
 * Joins each resolved [ResolvedComponentResult] to the [AggregatedDependencyRootMetadata] planned
 * for the same [RootKey], so metadata attribution never depends on [rootComponents] and
 * [rootMetadata] (populated by separate tasks, serialized through JSON) agreeing on list
 * position — positional pairing across that task boundary misattributes every root downstream
 * if the lists ever drift out of lockstep.
 *
 * [rootKeys] and [rootComponents] must already be index-aligned — that alignment is established
 * by construction in the single registrar loop that wires both properties (see
 * [com.grab.grazel.tasks.internal.WorkspaceDependencyInputsRegistrar.register]), not re-derived
 * here; this function only asserts the sizes agree before trusting the zip. Output order follows
 * [rootKeys] / [rootComponents] — a resolution-order requirement of
 * [AggregatedDependencyResolver.resolve].
 */
internal fun pairRootsByKey(
    rootKeys: List<RootKey>,
    rootComponents: List<ResolvedComponentResult>,
    rootMetadata: List<AggregatedDependencyRootMetadata>
): List<AggregatedDependencyRoot> {
    check(rootKeys.size == rootComponents.size) {
        "Workspace dependency root key count (${rootKeys.size}) does not match resolved " +
            "component count (${rootComponents.size}) — the registrar's single-loop index " +
            "alignment invariant was violated"
    }
    val metadataByKey = rootMetadata.associateBy { metadata -> metadata.rootKey() }
    check(metadataByKey.size == rootMetadata.size) {
        "Duplicate root keys in workspace dependency root metadata — keyed pairing unsafe"
    }
    check(rootComponents.size == rootMetadata.size) {
        "Workspace dependency root component count (${rootComponents.size}) does not match " +
            "metadata count (${rootMetadata.size})"
    }
    return rootKeys.zip(rootComponents).map { (key, component) ->
        val metadata = checkNotNull(metadataByKey[key]) {
            "No root metadata for resolved component key $key; " +
                "metadata keys: ${metadataByKey.keys.take(5)}..."
        }
        AggregatedDependencyRoot(component, metadata)
    }
}
