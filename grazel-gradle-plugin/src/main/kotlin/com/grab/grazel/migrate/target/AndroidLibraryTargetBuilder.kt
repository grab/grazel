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
import com.grab.grazel.gradle.variant.DefaultVariantCompressionService
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
    private val variantCompressionService: GradleProvider<DefaultVariantCompressionService>
) : TargetBuilder {

    override fun build(project: Project): List<BazelTarget> {
        // Check if compression result exists for this project
        val compressionResult = variantCompressionService.get().get(project.path)
        val libraryTargets = // Use pre-computed compressed targets from the analysis phase
            compressionResult?.targets?.map { it.toAndroidLibTarget() }
                ?: run {
                    // Fallback to extracting again
                    project.logger.error("Compressed result does not exist for this project")
                    variantMatcher.matchedVariants(project, VariantType.AndroidBuild)
                        .map { matchedVariant ->
                            androidLibraryDataExtractor
                                .extract(project, matchedVariant)
                                .toAndroidLibTarget()
                        }
                }
        return libraryTargets + unitTestsTargets(project)
    }

    private fun unitTestsTargets(project: Project): List<AndroidUnitTestTarget> {
        val compressionResult = variantCompressionService.get().get(project.path)
        val testVariants = variantMatcher.matchedVariants(project, VariantType.Test)
        return if (compressionResult != null) {
            // Deduplicate by compression suffix: only emit one test per unique suffix
            val variantsBySuffix = testVariants.groupBy { matchedVariant ->
                variantCompressionService.get().resolveSuffix(
                    projectPath = project.path,
                    variantName = matchedVariant.variantName,
                    fallbackSuffix = matchedVariant.nameSuffix,
                    logger = project.logger
                )
            }

            // Pick first variant alphabetically as representative for each suffix
            variantsBySuffix.values.map { variantsForSuffix ->
                val representative = variantsForSuffix.sortedBy { it.variantName }.first()
                unitTestDataExtractor.extract(project, representative).toUnitTestTarget()
            }
        } else {
            // Fallback: extract test for every variant
            project.logger.warn(
                "No compression result for ${project.path}, generating uncompressed unit test targets"
            )
            testVariants.map { matchedVariant ->
                unitTestDataExtractor.extract(project, matchedVariant).toUnitTestTarget()
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

