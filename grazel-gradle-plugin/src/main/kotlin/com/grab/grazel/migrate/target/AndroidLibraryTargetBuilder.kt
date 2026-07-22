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

package com.grab.grazel.migrate.target

import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.gradle.isAndroidTest
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanService
import com.grab.grazel.gradle.variant.DefaultVariantCompressionService
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.gradle.variant.VariantMatcher
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.variant.nameSuffix
import com.grab.grazel.gradle.variant.resolveSuffix
import com.grab.grazel.migrate.BazelTarget
import com.grab.grazel.migrate.TargetBuilder
import com.grab.grazel.migrate.android.AndroidLibraryData
import com.grab.grazel.migrate.android.AndroidLibraryDataExtractor
import com.grab.grazel.migrate.android.AndroidLibraryTarget
import com.grab.grazel.migrate.android.AndroidManifestParser
import com.grab.grazel.migrate.android.AndroidUnitTestData
import com.grab.grazel.migrate.android.AndroidUnitTestDataExtractor
import com.grab.grazel.migrate.android.AndroidUnitTestTarget
import com.grab.grazel.migrate.android.DefaultAndroidLibraryDataExtractor
import com.grab.grazel.migrate.android.DefaultAndroidManifestParser
import com.grab.grazel.migrate.android.DefaultAndroidUnitTestDataExtractor
import com.grab.grazel.migrate.android.toUnitTestTarget
import com.grab.grazel.util.GradleProvider
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import org.gradle.api.Project
import javax.inject.Inject
import javax.inject.Singleton

@Module
internal interface AndroidLibraryTargetBuilderModule {
    @Binds
    fun DefaultAndroidManifestParser.bindAndroidManifestParser(): AndroidManifestParser

    @Binds
    fun DefaultAndroidLibraryDataExtractor.bindAndroidLibraryDataExtractor(): AndroidLibraryDataExtractor

    @Binds
    fun DefaultAndroidUnitTestDataExtractor.bindAndroidUnitTestDataExtractor(): AndroidUnitTestDataExtractor

    @Binds
    @IntoSet
    fun AndroidLibraryTargetBuilder.bindAndroidLibraryTargetBuilder(): TargetBuilder
}

@Singleton
internal class AndroidLibraryTargetBuilder
@Inject
constructor(
    private val androidLibraryDataExtractor: AndroidLibraryDataExtractor,
    private val unitTestDataExtractor: AndroidUnitTestDataExtractor,
    private val variantMatcher: VariantMatcher,
    private val variantCompressionService: GradleProvider<DefaultVariantCompressionService>,
    private val dependencyResolutionService: GradleProvider<DefaultDependencyResolutionService>,
    private val workspaceRenderPlanService: GradleProvider<WorkspaceRenderPlanService>
) : TargetBuilder {

    override fun build(project: Project): List<BazelTarget> =
        selectLibraryData(project).map { it.toAndroidLibTarget() } +
            selectUnitTestData(project).map { unitTestData ->
                unitTestData.toUnitTestTarget()
            }

    override fun selectData(project: Project): List<TargetData> =
        selectLibraryData(project).map(::AndroidLibraryTargetData) +
            selectUnitTestData(project).map(::AndroidUnitTestTargetData)

    /**
     * The single selection of android_library data for this project: reachable (or referenced)
     * variants, folded through variant compression. Consumed by [build] for rendering and by
     * the reference-facts pass via [selectData] — one implementation, no facts/render drift.
     *
     * When compression yields a non-empty reachable-suffix set, the filtered map is used
     * as-is: `VariantCompressionResult`'s construction invariant guarantees every reachable
     * suffix is a `targetsBySuffix` key, so the filter cannot be empty (see
     * `VariantCompressionResultInvariantTest`).
     */
    internal fun selectLibraryData(project: Project): List<AndroidLibraryData> {
        val androidBuildVariants = reachableMatchedVariants(
            project = project,
            variantType = VariantType.AndroidBuild,
            variantMatcher = variantMatcher,
            variantCompressionService = variantCompressionService,
            dependencyResolutionService = dependencyResolutionService,
            workspaceRenderPlanService = workspaceRenderPlanService
        )
        val compressionResult = variantCompressionService.get().get(project.path)
            ?: run {
                project.logger.error("Compressed result does not exist for this project")
                return extractLibraryData(project, androidBuildVariants)
            }
        val reachableSuffixes = reachableCompressedTargetSuffixes(
            variantToSuffix = compressionResult.variantToSuffix,
            reachableVariantNames = androidBuildVariants.mapTo(mutableSetOf()) { variant ->
                variant.variantName
            }
        )
        if (reachableSuffixes.isEmpty()) {
            return extractLibraryData(project, androidBuildVariants)
        }
        return compressionResult.targetsBySuffix
            .filterKeys { suffix -> suffix in reachableSuffixes }
            .values
            .toList()
    }

    private fun extractLibraryData(
        project: Project,
        androidBuildVariants: Set<MatchedVariant>
    ): List<AndroidLibraryData> = androidBuildVariants.map { matchedVariant ->
        androidLibraryDataExtractor.extract(project, matchedVariant)
    }

    /**
     * When variant compression is active, multiple reachable test variants can resolve to the
     * same compressed suffix; extracting one [AndroidUnitTestData] per variant would produce
     * duplicate targets. Variants are grouped by resolved suffix and the alphabetically-first
     * variant per group is extracted as the stable representative.
     */
    internal fun selectUnitTestData(project: Project): List<AndroidUnitTestData> {
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
                    val representative = variantsForSuffix.minBy { it.variantName }
                    unitTestDataExtractor.extract(project, representative)
                }
        } else {
            project.logger.warn(
                "No compression result for ${project.path}, generating uncompressed unit test targets"
            )
            testVariants.map { matchedVariant ->
                unitTestDataExtractor.extract(project, matchedVariant)
            }
        }
    }

    override fun canHandle(project: Project): Boolean = with(project) {
        isAndroid && !isAndroidApplication && !isAndroidTest
    }
}

private fun AndroidLibraryData.toAndroidLibTarget() = AndroidLibraryTarget(
    name = name,
    srcs = srcs,
    resourceSets = resourceSets,
    deps = deps,
    plugins = plugins,
    enableDataBinding = databinding,
    enableCompose = compose,
    resValuesData = resValuesData,
    buildConfigData = buildConfigData,
    packageName = packageName,
    manifest = manifestFile,
    tags = tags,
    lintConfigData = lintConfigData
)
