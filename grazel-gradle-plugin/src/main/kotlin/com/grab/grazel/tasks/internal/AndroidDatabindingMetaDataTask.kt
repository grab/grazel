/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.dependencies.AggregatedDependencyRootKind
import com.grab.grazel.gradle.dependencies.WorkspaceDependencyRootInput
import com.grab.grazel.gradle.dependencies.externalModuleArtifacts
import com.grab.grazel.gradle.dependencies.mavenCoordinates
import com.grab.grazel.migrate.dependencies.databindingBazelName
import com.grab.grazel.migrate.dependencies.databindingPackage
import com.grab.grazel.migrate.dependencies.renderDatabindingInfoBazelrc
import com.grab.grazel.util.ansiGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register
import java.io.File

private const val AAR_EXTENSION = "aar"

/**
 * Generates the `databinding_info.bazelrc` that maps each external `aar` carrying databinding to the
 * package its generated `BR` class lives in.
 *
 * The `aar` set comes from the aggregated engine's main binary roots (see
 * [WorkspaceDependencyInputsRegistrar]), which are the only configurations the pipeline resolves
 * anyway — the task adds no resolution of its own.
 */
@CacheableTask
internal abstract class AndroidDatabindingMetaDataTask : DefaultTask() {

    /**
     * The `aar` files to scan. This is the task's only tracked input: an artifact whose coordinates
     * change also changes its file name (`NAME_ONLY`) or its bytes.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val databindingAars: ConfigurableFileCollection

    /**
     * The very artifact collections [databindingAars] is derived from, re-read in the task action to
     * recover each `aar`'s Maven coordinates. Holding the collections rather than their
     * configurations means the lenient artifact view is built once per root and its resolved result
     * serves both input snapshotting and the action, instead of being selected twice.
     *
     * Must stay `@Internal`: this task runs in the root project, and querying an
     * artifact-resolution *provider* (`ArtifactCollection.resolvedArtifacts`) for a subproject's
     * configuration fails with "does not hold the state lock for project" — during up-to-date
     * checking and in the action alike. Iterating an already-built collection does not.
     */
    @get:Internal
    abstract val databindingAarArtifacts: ListProperty<ArtifactCollection>

    @get:OutputFile
    abstract val databindingInfo: RegularFileProperty

    init {
        group = GRAZEL_TASK_GROUP
        description = "Generates databinding metadata"
    }

    @TaskAction
    fun action() {
        val aars = databindingAarArtifacts.get()
            .flatMap { artifacts -> artifacts.filter { artifact -> artifact.file.isAar } }
            .mapNotNull { artifact -> artifact.mavenCoordinates()?.to(artifact.file) }
            .distinctBy { (coordinates, _) -> coordinates.gav }
            .sortedBy { (coordinates, _) -> coordinates.gav }

        // The second dedup is by Bazel name, a coarser key than the GAV: two versions of one
        // artifact collapse to a single name. Sorting by GAV above gives that stable sort a defined
        // tie-break, so the winner never depends on classpath iteration order.
        val packagesByBazelName = runBlocking {
            aars.map { (coordinates, aar) ->
                async(Dispatchers.IO) { coordinates.databindingBazelName() to databindingPackage(aar) }
            }.awaitAll()
        }
            .mapNotNull { (bazelName, databindingPackage) ->
                databindingPackage?.let { bazelName to it }
            }
            .sortedBy { (bazelName, _) -> bazelName }
            .distinctBy { (bazelName, _) -> bazelName }
            .toMap(LinkedHashMap())

        val databindingInfoFile = databindingInfo.get().asFile
        databindingInfoFile.writeText(renderDatabindingInfoBazelrc(packagesByBazelName))
        logger.quiet("Generated ${databindingInfoFile.name}".ansiGreen)
    }

    companion object {
        private const val TASK_NAME = "generateDatabindingMetaData"

        /**
         * The root kinds databinding metadata is derived from. Main roots only: their classpaths are
         * the aggregated equivalent of the non-test Android variants this metadata has always come
         * from. Test and lint roots would contribute artifacts that never reached the generated
         * bazelrc.
         */
        private val DATABINDING_ROOT_KINDS = setOf(
            AggregatedDependencyRootKind.MAIN_HIERARCHY,
            AggregatedDependencyRootKind.MAIN_LEAF
        )

        fun register(
            @RootProject rootProject: Project,
            configureAction: AndroidDatabindingMetaDataTask.() -> Unit = {},
        ): TaskProvider<AndroidDatabindingMetaDataTask> = rootProject.tasks
            .register<AndroidDatabindingMetaDataTask>(TASK_NAME) {
                databindingInfo.set(
                    rootProject.layout.projectDirectory.file("databinding_info.bazelrc")
                )
                configureAction(this)
            }

        /**
         * Wires one workspace dependency root into [task], keeping [databindingAars] and
         * [databindingAarArtifacts] derived from a single artifact collection so the file inputs and
         * the coordinates read in the action can never describe different artifact sets. Roots
         * outside [DATABINDING_ROOT_KINDS] are skipped here rather than by the caller, so the rule
         * about which roots feed databinding lives with the feature that owns it.
         */
        internal fun addDatabindingAarArtifacts(
            task: AndroidDatabindingMetaDataTask,
            rootInput: WorkspaceDependencyRootInput
        ) {
            if (rootInput.kind !in DATABINDING_ROOT_KINDS) return
            val artifacts = rootInput.configuration.externalModuleArtifacts()
            task.databindingAarArtifacts.add(artifacts)
            task.databindingAars.from(artifacts.artifactFiles.filter { file -> file.isAar })
        }
    }
}

private val File.isAar: Boolean get() = extension == AAR_EXTENSION
