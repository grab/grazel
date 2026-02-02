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

import com.grab.grazel.bazel.starlark.BazelDependency.ProjectDependency
import com.grab.grazel.di.GrazelComponent
import com.grab.grazel.gradle.dependencies.DefaultDependencyGraphsService
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.TopologicalSorter
import com.grab.grazel.gradle.isAndroidLibrary
import com.grab.grazel.gradle.variant.DefaultVariantCompressionService
import com.grab.grazel.gradle.variant.VariantCompressionDecision.Compressed
import com.grab.grazel.gradle.variant.VariantCompressionDecision.Expanded
import com.grab.grazel.gradle.variant.VariantCompressionDecision.FullyCompressed
import com.grab.grazel.gradle.variant.VariantCompressionDecision.SingleVariant
import com.grab.grazel.gradle.variant.VariantCompressionResult
import com.grab.grazel.gradle.variant.VariantCompressor
import com.grab.grazel.gradle.variant.VariantMatcher
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.migrate.android.AndroidLibraryDataExtractor
import com.grab.grazel.util.GradleProvider
import com.grab.grazel.util.Json
import dagger.Lazy
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.UntrackedTask
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import java.time.Instant
import javax.inject.Inject

/**
 * Task that analyzes Android library projects and computes variant compression results.
 *
 * This task:
 * 1. Processes projects in topological order
 * 2. For each Android library project, extracts variant data
 * 3. Applies compression logic
 * 4. Registers compression results in the [DefaultVariantCompressionService]
 * 5. Writes a JSON summary to the output file
 */
@UntrackedTask(because = "Up to dateness not implemented correctly")
internal open class AnalyzeVariantCompressionTask
@Inject
constructor(
    private val androidLibraryDataExtractor: Lazy<AndroidLibraryDataExtractor>,
    private val variantMatcher: Lazy<VariantMatcher>,
    private val variantCompressor: Lazy<VariantCompressor>,
    private val dependencyGraphsService: GradleProvider<DefaultDependencyGraphsService>,
    private val variantCompressionService: GradleProvider<DefaultVariantCompressionService>
) : DefaultTask() {

    @get:InputFile
    val workspaceDependencies: RegularFileProperty = project.objects.fileProperty()

    @get:OutputFile
    val compressionResultsFile: RegularFileProperty = project.objects.fileProperty()

    @get:Internal
    val dependencyResolutionService: Property<DefaultDependencyResolutionService> =
        project.objects.property()

    @TaskAction
    fun action() {
        dependencyResolutionService
            .get()
            .init(workspaceDependencies.get().asFile)

        val graphs = dependencyGraphsService.get().get()
        val orderedProjects = TopologicalSorter.sort(graphs)

        val projectSummaries = mutableListOf<ProjectSummary>()

        orderedProjects.forEach { project ->
            if (project.isAndroidLibrary) {
                try {
                    val compressionResult = analyzeProject(project)
                    variantCompressionService.get().register(project.path, compressionResult)
                    projectSummaries.add(
                        ProjectSummary(
                            path = project.path,
                            variantCount = compressionResult.variantToSuffix.size,
                            suffixes = compressionResult.suffixes.toList()
                        )
                    )

                    logger.info(
                        "Analyzed ${project.path}: ${compressionResult.suffixes.size} targets " +
                            "from ${compressionResult.variantToSuffix.size} variants"
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to analyze ${project.path}: ${e.message}")
                }
            }
        }

        val summary = AnalysisSummary(
            analysisTimestamp = Instant.now().toString(),
            projectCount = projectSummaries.size,
            projects = projectSummaries
        )

        compressionResultsFile.get().asFile.writeText(Json.encodeToString(summary))

        logger.info("Variant compression analysis complete. Results written to ${compressionResultsFile.get().asFile}")
    }

    /** Analyzes a single Android library project and returns a compression result. */
    private fun analyzeProject(project: Project): VariantCompressionResult {
        val variants = variantMatcher.get().matchedVariants(project, VariantType.AndroidBuild)

        // Extract AndroidLibraryData for each variant
        val variantData = variants.associate { matchedVariant ->
            matchedVariant.variantName to
                androidLibraryDataExtractor.get().extract(project, matchedVariant)
        }

        // Collect compression results from dependencies
        val dependencyVariantCompressionResults = mutableMapOf<Project, VariantCompressionResult>()
        val service = variantCompressionService.get()

        // Get all direct project dependencies
        val projectDependencies = variantData.values
            .flatMap { it.deps }
            .filterIsInstance<ProjectDependency>()
            .map { it.dependencyProject }
            .toSet()

        // Collect their compression results
        projectDependencies.forEach { depProject ->
            service.get(depProject.path)?.let { result ->
                dependencyVariantCompressionResults[depProject] = result
            }
        }

        // Extract build type from variant name
        // Assumes variant name follows pattern like "freeDebug", "paidRelease"
        // Build type is the last component when split by capital letters
        fun buildTypeFn(variantName: String): String {
            // Find the matched variant to get the actual build type
            val matchedVariant = variants.find { it.variantName == variantName }
            return matchedVariant?.buildType ?: "debug"
        }

        // Compress variants using the compressor
        val resultWithDecisions = variantCompressor.get().compress(
            variants = variantData,
            buildTypeFn = ::buildTypeFn,
            dependencyVariantCompressionResults = dependencyVariantCompressionResults
        )

        // Log detailed decision for each build type group
        resultWithDecisions.decisions.forEach { decision ->
            when (decision) {
                is Compressed -> logger.info(
                    "${project.path} compressed ${decision.variants} → [${decision.compressedSuffix}]"
                )

                is Expanded -> logger.info(
                    "${project.path} kept expanded ${decision.variants} (${decision.reason})"
                )

                is SingleVariant -> logger.info(
                    "${project.path} single variant ${decision.variant} → [${decision.suffix}]"
                )

                is FullyCompressed -> logger.info(
                    "${project.path} fully compressed [${decision.buildTypes.joinToString()}] → single target"
                )
            }
        }
        return resultWithDecisions.result
    }

    companion object {
        private const val TASK_NAME = "analyzeVariantCompression"

        internal fun register(
            rootProject: Project,
            grazelComponent: GrazelComponent,
            configureAction: AnalyzeVariantCompressionTask.() -> Unit = {}
        ): TaskProvider<AnalyzeVariantCompressionTask> {
            return rootProject.tasks.register<AnalyzeVariantCompressionTask>(
                TASK_NAME,
                grazelComponent.androidLibraryDataExtractor(),
                grazelComponent.variantMatcher(),
                grazelComponent.variantCompressor(),
                grazelComponent.dependencyGraphsService(),
                grazelComponent.variantCompressionService()
            ).apply {
                configure {
                    group = GRAZEL_TASK_GROUP
                    description =
                        "Analyze variant compression opportunities across all Android library projects"
                    compressionResultsFile.set(
                        rootProject.layout.buildDirectory.file("grazel/compression-results.json")
                    )
                    dependencyResolutionService.set(grazelComponent.dependencyResolutionService())
                    configureAction(this)
                }
            }
        }
    }
}

@Serializable
private data class AnalysisSummary(
    val analysisTimestamp: String,
    val projectCount: Int,
    val projects: List<ProjectSummary>
)

@Serializable
private data class ProjectSummary(
    val path: String,
    val variantCount: Int,
    val suffixes: List<String>
)
