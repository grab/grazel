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

package com.grab.grazel.gradle.variant

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.migrate.android.AndroidLibraryData
import org.gradle.api.Project
import javax.inject.Inject

/** Describes why compression succeeded or failed for a build type group */
internal sealed class VariantCompressionDecision {
    data class Compressed(
        val buildType: String,
        val variants: List<String>,
        val compressedSuffix: String
    ) : VariantCompressionDecision()

    data class Expanded(
        val buildType: String,
        val variants: List<String>,
        val reason: String
    ) : VariantCompressionDecision()

    data class SingleVariant(
        val buildType: String,
        val variant: String,
        val suffix: String
    ) : VariantCompressionDecision()

    /**
     * Represents full compression across all build types into a single target. This occurs when all
     * build-type targets are equivalent and all dependencies are also fully compressed.
     */
    data class FullyCompressed(
        val buildTypes: List<String>,
        val variants: List<String>
    ) : VariantCompressionDecision()
}

internal data class CompressionResultWithDecisions(
    val result: VariantCompressionResult,
    val decisions: List<VariantCompressionDecision>
)

// =============================================================================
// Internal Types for Flavor Compression
// =============================================================================

/**
 * Represents the decision for how to handle variants within a single build type.
 *
 * This sealed class encapsulates the three possible outcomes when processing variants
 * for a build type during flavor compression.
 */
private sealed class BuildTypeDecision {
    /** Compress all variants into a single target with the build type as suffix */
    data class Compress(
        val buildType: String,
        val suffix: String,
        val variants: Map<String, AndroidLibraryData>
    ) : BuildTypeDecision()

    /** Keep each variant as a separate target (compression blocked or variants differ) */
    data class Expand(
        val buildType: String,
        val reason: String,
        val variants: Map<String, AndroidLibraryData>
    ) : BuildTypeDecision()

    /** Only one variant exists - nothing to compress */
    data class Single(
        val buildType: String,
        val variantName: String,
        val data: AndroidLibraryData
    ) : BuildTypeDecision()
}

/**
 * Result of applying a [BuildTypeDecision] to produce targets and mappings.
 */
private data class BuildTypeResult(
    val targetsBySuffix: Map<String, AndroidLibraryData>,
    val variantToSuffix: Map<String, String>,
    val isExpanded: Boolean,
    val decision: VariantCompressionDecision
)

/**
 * Aggregated result from flavor compression (compressing variants within each build type).
 *
 * Contains all the data accumulated from processing each build type, ready for
 * potential build-type compression (merging across build types).
 */
private data class FlavorCompressionResult(
    val targetsBySuffix: Map<String, AndroidLibraryData>,
    val variantToSuffix: Map<String, String>,
    val expandedBuildTypes: Set<String>,
    val decisions: List<VariantCompressionDecision>
) {
    fun toCompressionResult() = VariantCompressionResult(
        targetsBySuffix = targetsBySuffix,
        variantToSuffix = variantToSuffix,
        expandedBuildTypes = expandedBuildTypes
    )

    fun toResultWithDecisions() = CompressionResultWithDecisions(
        result = toCompressionResult(),
        decisions = decisions
    )
}

/**
 * Compresses Android variant targets by grouping equivalent variants together.
 *
 * Compression reduces the number of Bazel targets by identifying variants that have identical build
 * configuration and combining them into a single target per build type.
 */
internal interface VariantCompressor {
    /**
     * Compresses variants based on equivalence analysis.
     *
     * @param variants Map of variant name to AndroidLibraryData
     * @param buildTypeFn Function to extract build type name from variant name
     * @param dependencyVariantCompressionResults Map of dependency project to their
     *    CompressionResult
     * @return CompressionResultWithDecisions containing compressed targets, mappings, and decision
     *    info
     */
    fun compress(
        variants: Map<String, AndroidLibraryData>,
        buildTypeFn: (String) -> String,
        dependencyVariantCompressionResults: Map<Project, VariantCompressionResult>
    ): CompressionResultWithDecisions
}

internal class DefaultVariantCompressor @Inject constructor(
    private val equivalenceChecker: VariantEquivalenceChecker
) : VariantCompressor {

    // =========================================================================
    // Public API
    // =========================================================================

    override fun compress(
        variants: Map<String, AndroidLibraryData>,
        buildTypeFn: (String) -> String,
        dependencyVariantCompressionResults: Map<Project, VariantCompressionResult>
    ): CompressionResultWithDecisions {
        if (variants.isEmpty()) {
            return CompressionResultWithDecisions(
                result = VariantCompressionResult.empty(),
                decisions = emptyList()
            )
        }

        // Group variants by build type
        val variantsByBuildType = variants.entries.groupBy { (variantName, _) ->
            buildTypeFn(variantName)
        }

        // Compress flavors within each build type (e.g., freeDebug + paidDebug → debug)
        val flavorCompressed = compressFlavors(variantsByBuildType, dependencyVariantCompressionResults)

        // Try to compress across build types (e.g., debug + release → single target)
        return if (canFullyCompress(flavorCompressed, dependencyVariantCompressionResults)) {
            applyFullCompression(flavorCompressed)
        } else {
            flavorCompressed.toResultWithDecisions()
        }
    }

    // =========================================================================
    // Flavor Compression (within each build type)
    // =========================================================================

    /**
     * Processes each build type and aggregates results into a FlavorCompressionResult.
     */
    private fun compressFlavors(
        variantsByBuildType: Map<String, List<Map.Entry<String, AndroidLibraryData>>>,
        dependencyResults: Map<Project, VariantCompressionResult>
    ): FlavorCompressionResult {
        val results = variantsByBuildType.map { (buildType, variantGroup) ->
            val variants = variantGroup.associate { it.key to it.value }
            val decision = decideBuildTypeCompression(buildType, variants, dependencyResults)
            applyBuildTypeDecision(decision)
        }

        return mergeResults(results)
    }

    /**
     * Decides how to handle variants for a single build type.
     *
     * Evaluates whether variants can be compressed, must be expanded, or are singular.
     */
    private fun decideBuildTypeCompression(
        buildType: String,
        variants: Map<String, AndroidLibraryData>,
        dependencyResults: Map<Project, VariantCompressionResult>
    ): BuildTypeDecision {
        // Single variant - nothing to compress
        if (variants.size == 1) {
            val (name, data) = variants.entries.first()
            return BuildTypeDecision.Single(buildType, name, data)
        }

        // Check if compression is blocked by dependencies
        val isBlocked = isCompressionBlocked(variants, buildType, dependencyResults)
        if (isBlocked) {
            val blockingDeps = findBlockingDependencies(variants, buildType, dependencyResults)
            return BuildTypeDecision.Expand(
                buildType = buildType,
                reason = "blocked by dependencies: ${blockingDeps.joinToString(", ")}",
                variants = variants
            )
        }

        // Check if all variants are equivalent
        val allEquivalent = areAllVariantsEquivalent(variants.values.toList())
        if (!allEquivalent) {
            return BuildTypeDecision.Expand(
                buildType = buildType,
                reason = "variants differ in configuration",
                variants = variants
            )
        }

        // All conditions met - compress
        return BuildTypeDecision.Compress(
            buildType = buildType,
            suffix = normalizeVariantSuffix(buildType),
            variants = variants
        )
    }

    /**
     * Applies a [BuildTypeDecision] to produce targets and mappings.
     */
    private fun applyBuildTypeDecision(decision: BuildTypeDecision): BuildTypeResult =
        when (decision) {
            is BuildTypeDecision.Compress -> applyCompression(decision)
            is BuildTypeDecision.Expand -> applyExpansion(decision)
            is BuildTypeDecision.Single -> applySingle(decision)
        }

    private fun applyCompression(decision: BuildTypeDecision.Compress): BuildTypeResult {
        val sortedVariants = decision.variants.entries.sortedBy { it.key }
        val representative = sortedVariants.first()

        // Derive compressed name from representative
        val representativeSuffix = normalizeVariantSuffix(representative.key)
        val baseName = representative.value.name.removeSuffix(representativeSuffix)
        val compressedData = representative.value.copy(name = baseName + decision.suffix)

        // All variants map to the compressed suffix
        val variantMappings = decision.variants.keys.associateWith { decision.suffix }

        return BuildTypeResult(
            targetsBySuffix = mapOf(decision.suffix to compressedData),
            variantToSuffix = variantMappings,
            isExpanded = false,
            decision = VariantCompressionDecision.Compressed(
                buildType = decision.buildType,
                variants = decision.variants.keys.sorted(),
                compressedSuffix = decision.suffix
            )
        )
    }

    private fun applyExpansion(decision: BuildTypeDecision.Expand): BuildTypeResult {
        val targetsBySuffix = mutableMapOf<String, AndroidLibraryData>()
        val variantToSuffix = mutableMapOf<String, String>()

        decision.variants.forEach { (variantName, data) ->
            val suffix = normalizeVariantSuffix(variantName)
            targetsBySuffix[suffix] = data
            variantToSuffix[variantName] = suffix
        }

        return BuildTypeResult(
            targetsBySuffix = targetsBySuffix,
            variantToSuffix = variantToSuffix,
            isExpanded = true,
            decision = VariantCompressionDecision.Expanded(
                buildType = decision.buildType,
                variants = decision.variants.keys.sorted(),
                reason = decision.reason
            )
        )
    }

    private fun applySingle(decision: BuildTypeDecision.Single): BuildTypeResult {
        val suffix = normalizeVariantSuffix(decision.variantName)

        return BuildTypeResult(
            targetsBySuffix = mapOf(suffix to decision.data),
            variantToSuffix = mapOf(decision.variantName to suffix),
            isExpanded = false,
            decision = VariantCompressionDecision.SingleVariant(
                buildType = decision.buildType,
                variant = decision.variantName,
                suffix = suffix
            )
        )
    }

    /**
     * Merges multiple [BuildTypeResult]s into a single [FlavorCompressionResult].
     */
    private fun mergeResults(results: List<BuildTypeResult>): FlavorCompressionResult {
        val targetsBySuffix = mutableMapOf<String, AndroidLibraryData>()
        val variantToSuffix = mutableMapOf<String, String>()
        val expandedBuildTypes = mutableSetOf<String>()
        val decisions = mutableListOf<VariantCompressionDecision>()

        for (result in results) {
            targetsBySuffix.putAll(result.targetsBySuffix)
            variantToSuffix.putAll(result.variantToSuffix)
            decisions.add(result.decision)

            if (result.isExpanded) {
                val buildType = when (val d = result.decision) {
                    is VariantCompressionDecision.Expanded -> d.buildType
                    else -> continue
                }
                expandedBuildTypes.add(buildType)
            }
        }

        return FlavorCompressionResult(
            targetsBySuffix = targetsBySuffix,
            variantToSuffix = variantToSuffix,
            expandedBuildTypes = expandedBuildTypes,
            decisions = decisions
        )
    }

    // =========================================================================
    // Build-Type Compression (across build types)
    // =========================================================================

    /**
     * Checks if full cross-build-type compression is possible.
     *
     * Full compression requires:
     * 1. Flavor compression succeeded (no expanded build types)
     * 2. More than one build-type target to compress
     * 3. All build-type targets are equivalent
     * 4. All dependencies are fully compressed
     */
    private fun canFullyCompress(
        flavorCompressed: FlavorCompressionResult,
        dependencyResults: Map<Project, VariantCompressionResult>
    ): Boolean {
        // 1. Flavor compression must have succeeded (no expanded build types)
        if (flavorCompressed.expandedBuildTypes.isNotEmpty()) return false

        // 2. Must have more than one build-type target to compress
        if (flavorCompressed.targetsBySuffix.size <= 1) return false

        // 3. All build-type targets must be equivalent
        val targets = flavorCompressed.targetsBySuffix.values.toList()
        val first = targets.first()
        val allEquivalent = targets.drop(1).all { equivalenceChecker.areEquivalent(first, it) }
        if (!allEquivalent) return false

        // 4. All dependencies must be fully compressed
        val projectDeps = targets
            .flatMap { it.deps }
            .filterIsInstance<BazelDependency.ProjectDependency>()
            .map { it.dependencyProject }
            .toSet()

        return projectDeps.all { dep ->
            dependencyResults[dep]?.isFullyCompressed ?: true
        }
    }

    /**
     * Performs full cross-build-type compression.
     *
     * Creates a single target with no suffix by combining all build-type targets.
     */
    private fun applyFullCompression(flavorCompressed: FlavorCompressionResult): CompressionResultWithDecisions {
        // Pick representative (first alphabetically by suffix)
        val representativeSuffix = flavorCompressed.targetsBySuffix.keys.minOf { it }
        val representativeData = flavorCompressed.targetsBySuffix.getValue(representativeSuffix)

        // Update name to remove suffix
        val baseName = representativeData.name.removeSuffix(representativeSuffix)
        val fullyCompressedData = representativeData.copy(name = baseName)

        // All variants map to empty suffix
        val newVariantToSuffix = flavorCompressed.variantToSuffix.mapValues { "" }

        val fullyCompressedDecision = VariantCompressionDecision.FullyCompressed(
            buildTypes = flavorCompressed.targetsBySuffix.keys.map { it.removePrefix("-") },
            variants = flavorCompressed.variantToSuffix.keys.toList()
        )

        return CompressionResultWithDecisions(
            result = VariantCompressionResult(
                targetsBySuffix = mapOf("" to fullyCompressedData),
                variantToSuffix = newVariantToSuffix,
                expandedBuildTypes = emptySet()
            ),
            decisions = flavorCompressed.decisions + fullyCompressedDecision
        )
    }

    // =========================================================================
    // Shared Helpers
    // =========================================================================

    /**
     * Checks if compression is blocked for a build type because any direct dependency is expanded
     * for that build type.
     */
    private fun isCompressionBlocked(
        variants: Map<String, AndroidLibraryData>,
        buildType: String,
        dependencyResults: Map<Project, VariantCompressionResult>
    ): Boolean {
        val projectDependencies = extractProjectDependencies(variants)
        return projectDependencies.any { depProject ->
            dependencyResults[depProject]?.isExpanded(buildType) ?: false
        }
    }

    /**
     * Finds the project dependencies that are blocking compression for a given build type.
     */
    private fun findBlockingDependencies(
        variants: Map<String, AndroidLibraryData>,
        buildType: String,
        dependencyResults: Map<Project, VariantCompressionResult>
    ): List<String> {
        val projectDependencies = extractProjectDependencies(variants)
        return projectDependencies
            .filter { dependencyResults[it]?.isExpanded(buildType) == true }
            .map { it.path }
    }

    /**
     * Extracts all unique project dependencies from a set of variants.
     */
    private fun extractProjectDependencies(
        variants: Map<String, AndroidLibraryData>
    ): Set<Project> = variants.values
        .flatMap { it.deps }
        .filterIsInstance<BazelDependency.ProjectDependency>()
        .map { it.dependencyProject }
        .toSet()

    /**
     * Checks if all variants in a list are equivalent to each other.
     */
    private fun areAllVariantsEquivalent(variants: List<AndroidLibraryData>): Boolean {
        if (variants.size <= 1) return true
        val first = variants.first()
        return variants.drop(1).all { equivalenceChecker.areEquivalent(first, it) }
    }
}
