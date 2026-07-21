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

package com.grab.grazel.migrate.target

import com.grab.grazel.gradle.hasTestInstrumentationRunner
import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.gradle.isAndroidTest
import com.grab.grazel.gradle.isKotlin
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.TargetReferenceFactsCollector
import com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanService
import com.grab.grazel.gradle.dependencies.merged
import com.grab.grazel.gradle.dependencies.model.TargetReferenceFacts
import com.grab.grazel.gradle.variant.DefaultVariantCompressionService
import com.grab.grazel.gradle.variant.VariantCompressionResult
import com.grab.grazel.gradle.variant.VariantMatcher
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.nameSuffix
import com.grab.grazel.gradle.variant.normalizeVariantSuffix
import com.grab.grazel.gradle.variant.resolveSuffix
import com.grab.grazel.migrate.android.AndroidBinaryData
import com.grab.grazel.migrate.android.AndroidBinaryDataExtractor
import com.grab.grazel.migrate.android.AndroidInstrumentationBinaryData
import com.grab.grazel.migrate.android.AndroidInstrumentationBinaryDataExtractor
import com.grab.grazel.migrate.android.AndroidLibraryData
import com.grab.grazel.migrate.android.AndroidLibraryDataExtractor
import com.grab.grazel.migrate.android.AndroidTestData
import com.grab.grazel.migrate.android.AndroidTestDataExtractor
import com.grab.grazel.migrate.android.AndroidUnitTestData
import com.grab.grazel.migrate.android.AndroidUnitTestDataExtractor
import com.grab.grazel.migrate.android.SourceSetType
import com.grab.grazel.migrate.kotlin.KotlinProjectData
import com.grab.grazel.migrate.kotlin.KotlinProjectDataExtractor
import com.grab.grazel.migrate.kotlin.KotlinUnitTestDataExtractor
import com.grab.grazel.migrate.kotlin.UnitTestData
import com.grab.grazel.util.GradleProvider
import org.gradle.api.Project
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class TargetReferenceFactsExtractor
@Inject
constructor(
    private val androidLibraryDataExtractor: AndroidLibraryDataExtractor,
    private val androidBinaryDataExtractor: AndroidBinaryDataExtractor,
    private val androidUnitTestDataExtractor: AndroidUnitTestDataExtractor,
    private val androidInstrumentationBinaryDataExtractor: AndroidInstrumentationBinaryDataExtractor,
    private val androidTestDataExtractor: AndroidTestDataExtractor,
    private val kotlinProjectDataExtractor: KotlinProjectDataExtractor,
    private val kotlinUnitTestDataExtractor: KotlinUnitTestDataExtractor,
    private val variantMatcher: VariantMatcher,
    private val variantCompressionService: GradleProvider<DefaultVariantCompressionService>,
    private val dependencyResolutionService: GradleProvider<DefaultDependencyResolutionService>,
    private val workspaceRenderPlanService: GradleProvider<WorkspaceRenderPlanService>
) {

    fun collect(project: Project): TargetReferenceFacts =
        when {
            project.isAndroidApplication -> listOf(
                androidBinaryFacts(project),
                androidInstrumentationFacts(project)
            ).merged()
            project.isAndroid && !project.isAndroidTest -> androidLibraryFacts(project)
            project.isAndroidTest -> standaloneAndroidTestFacts(project)
            project.isKotlin -> kotlinFacts(project)
            else -> TargetReferenceFacts()
        }

    private fun androidLibraryFacts(project: Project): TargetReferenceFacts =
        (
            androidLibraryData(project).map(AndroidLibraryData::referenceFacts) +
                androidUnitTestData(project).map(AndroidUnitTestData::referenceFacts)
            ).merged()

    /**
     * An app variant is included if it is reachable through the normal bucket-hierarchy
     * predicate ([isReachableTargetVariant]) OR its generated binary target name is directly
     * referenced by another already-rendered target ([isReferencedGeneratedTarget]). The fallback
     * matters because a variant can be a legitimate build target even when nothing in the
     * dependency graph reaches its configuration bucket - it may only be pulled in via a
     * target-name reference recorded in the render plan from a previous pass.
     */
    private fun androidBinaryFacts(project: Project): TargetReferenceFacts {
        val isReachableBucket = reachableBucketPredicate(project, dependencyResolutionService)
        val referencedTargetNames = workspaceRenderPlanService.get().referencedTargetNames(project.path)
        return variantMatcher.matchedVariants(
            project = project,
            variantType = VariantType.AndroidBuild,
            appVariantFilter = { appVariant ->
                appVariant.isReachableTargetVariant(isReachableBucket) ||
                    isReferencedGeneratedTarget(
                        targetName = "${project.name}${normalizeVariantSuffix(appVariant.name)}",
                        referencedTargetNames = referencedTargetNames
                    )
            }
        ).map { matchedVariant ->
            val androidLibraryData = androidLibraryDataExtractor.extract(project, matchedVariant)
            val androidBinaryData = androidBinaryDataExtractor.extract(project, matchedVariant)
            androidBinaryReferenceFacts(androidLibraryData, androidBinaryData)
        }
            .merged()
    }

    private fun androidInstrumentationFacts(project: Project): TargetReferenceFacts {
        if (!project.hasTestInstrumentationRunner) return TargetReferenceFacts()
        val isReachableBucket = reachableBucketPredicate(project, dependencyResolutionService)
        return variantMatcher.matchedVariants(
            project = project,
            variantType = VariantType.AndroidTest,
            appVariantFilter = { appVariant -> appVariant.isReachableTargetVariant(isReachableBucket) }
        ).map { matchedVariant ->
            androidInstrumentationBinaryDataExtractor.extract(
                project = project,
                matchedVariant = matchedVariant,
                sourceSetType = SourceSetType.JAVA_KOTLIN,
            )
        }
            .filter(AndroidInstrumentationBinaryData::hasSources)
            .map(AndroidInstrumentationBinaryData::referenceFacts)
            .merged()
    }

    private fun standaloneAndroidTestFacts(project: Project): TargetReferenceFacts {
        val isReachableBucket = reachableBucketPredicate(project, dependencyResolutionService)
        val referencedTargetNames = workspaceRenderPlanService.get().referencedTargetNames(project.path)
        return variantMatcher.matchedVariants(
            project = project,
            variantType = VariantType.AndroidBuild,
        ).filter { matchedVariant ->
            matchedVariant.isReachableProjectVariant(isReachableBucket) ||
                isReferencedGeneratedTarget(
                    targetName = "${project.name}${matchedVariant.nameSuffix}",
                    referencedTargetNames = referencedTargetNames
                )
        }.map { matchedVariant ->
            val androidLibraryData = androidLibraryDataExtractor.extract(project, matchedVariant)
            val androidBinaryData = androidBinaryDataExtractor.extract(project, matchedVariant)
            androidTestDataExtractor.extract(
                project = project,
                matchedVariant = matchedVariant,
                androidLibraryData = androidLibraryData,
                androidBinaryData = androidBinaryData
            ).referenceFacts()
        }
            .merged()
    }

    private fun kotlinFacts(project: Project): TargetReferenceFacts {
        if (!isReachableJvmProject(project, dependencyResolutionService, workspaceRenderPlanService)) {
            return TargetReferenceFacts()
        }
        val projectData = kotlinProjectDataExtractor.extract(project)
        val testData = kotlinUnitTestDataExtractor.extract(project)
        return listOf(
            projectData.referenceFacts(),
            testData.referenceFacts()
        ).merged()
    }

    private fun androidLibraryData(project: Project): List<AndroidLibraryData> {
        val androidBuildVariants = reachableMatchedVariants(
            project = project,
            variantType = VariantType.AndroidBuild,
            variantMatcher = variantMatcher,
            variantCompressionService = variantCompressionService,
            dependencyResolutionService = dependencyResolutionService,
            workspaceRenderPlanService = workspaceRenderPlanService
        )
        val compressionResult = variantCompressionService.get().get(project.path)
        return compressionResult?.reachableAndroidLibraryData(
            reachableVariantNames = androidBuildVariants.mapTo(mutableSetOf()) { variant ->
                variant.variantName
            }
        )?.takeUnless(List<AndroidLibraryData>::isEmpty)
            ?: androidBuildVariants.map { matchedVariant ->
                androidLibraryDataExtractor.extract(project, matchedVariant)
            }
    }

    /**
     * When variant compression is active, multiple reachable test variants can resolve to the
     * same compressed suffix; emitting one extracted [AndroidUnitTestData] per variant in that
     * case would produce duplicate/ambiguous test targets. Instead, variants are grouped by their
     * resolved suffix and only the alphabetically-first (`minBy variantName`) variant per group is
     * extracted, giving a stable, deterministic representative across runs.
     */
    private fun androidUnitTestData(project: Project): List<AndroidUnitTestData> {
        val compressionResult = variantCompressionService.get().get(project.path)
        val testVariants = reachableMatchedVariants(
            project = project,
            variantType = VariantType.Test,
            variantMatcher = variantMatcher,
            variantCompressionService = variantCompressionService,
            dependencyResolutionService = dependencyResolutionService,
            workspaceRenderPlanService = workspaceRenderPlanService
        )
        return if (compressionResult != null) {
            testVariants
                .groupBy { matchedVariant ->
                    variantCompressionService.get().resolveSuffix(
                        projectPath = project.path,
                        variantName = matchedVariant.variantName,
                        fallbackSuffix = matchedVariant.nameSuffix,
                        logger = project.logger
                    )
                }
                .values
                .map { variantsForSuffix ->
                    val representative = variantsForSuffix.minBy { variant -> variant.variantName }
                    androidUnitTestDataExtractor.extract(project, representative)
                }
        } else {
            testVariants.map { matchedVariant ->
                androidUnitTestDataExtractor.extract(project, matchedVariant)
            }
        }
    }

    /**
     * [targetsBySuffix] is keyed by *compressed* target suffix, not variant name, so reachable
     * variant names must first be translated through [variantToSuffix] before they can be used to
     * filter it. Skipping this indirection (e.g. filtering `targetsBySuffix` by variant name
     * directly) would silently drop every entry since the keys never match.
     */
    private fun VariantCompressionResult.reachableAndroidLibraryData(
        reachableVariantNames: Set<String>
    ): List<AndroidLibraryData> {
        val reachableSuffixes = reachableCompressedTargetSuffixes(
            variantToSuffix = variantToSuffix,
            reachableVariantNames = reachableVariantNames
        )
        return targetsBySuffix
            .filterKeys { suffix -> suffix in reachableSuffixes }
            .values
            .toList()
    }
}

private fun AndroidLibraryData.referenceFacts(): TargetReferenceFacts =
    TargetReferenceFactsCollector.from(
        deps = deps,
        tags = tags,
        plugins = plugins,
        lintChecks = lintConfigData.lintChecks.orEmpty()
    )

private fun androidBinaryReferenceFacts(
    androidLibraryData: AndroidLibraryData,
    androidBinaryData: AndroidBinaryData
): TargetReferenceFacts =
    TargetReferenceFactsCollector.from(
        deps = androidLibraryData.deps + androidBinaryData.deps,
        plugins = androidLibraryData.plugins,
        lintChecks = androidLibraryData.lintConfigData.lintChecks.orEmpty()
    )

internal fun AndroidUnitTestData.referenceFacts(): TargetReferenceFacts =
    TargetReferenceFactsCollector.from(
        deps = deps,
        tags = tags,
        associates = associates
    )

internal fun AndroidInstrumentationBinaryData.referenceFacts(): TargetReferenceFacts =
    TargetReferenceFactsCollector.from(
        deps = deps,
        tags = tags,
        associates = associates,
        instruments = instruments
    )

private fun AndroidInstrumentationBinaryData.hasSources(): Boolean = srcs.isNotEmpty()

private fun AndroidTestData.referenceFacts(): TargetReferenceFacts =
    TargetReferenceFactsCollector.from(
        deps = deps,
        tags = tags,
        lintChecks = lintConfigData.lintChecks.orEmpty(),
        associates = associates,
        instruments = instruments
    )

private fun KotlinProjectData.referenceFacts(): TargetReferenceFacts =
    TargetReferenceFactsCollector.from(
        deps = deps,
        tags = tags,
        plugins = plugins,
        lintChecks = lintConfigData.lintChecks.orEmpty()
    )

internal fun UnitTestData.referenceFacts(): TargetReferenceFacts =
    TargetReferenceFactsCollector.from(
        deps = deps,
        tags = tags,
        associates = associates
    )
