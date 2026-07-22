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

import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanService
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.hasCrashlytics
import com.grab.grazel.gradle.hasGooglePlayServicesPlugin
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.gradle.variant.VariantMatcher
import com.grab.grazel.gradle.variant.nameSuffix
import com.grab.grazel.gradle.variant.normalizeVariantSuffix
import com.grab.grazel.migrate.BazelTarget
import com.grab.grazel.migrate.TargetBuilder
import com.grab.grazel.migrate.android.AndroidBinaryDataExtractor
import com.grab.grazel.migrate.android.AndroidBinaryTarget
import com.grab.grazel.migrate.android.AndroidLibraryDataExtractor
import com.grab.grazel.migrate.android.CrashlyticsDataExtractor
import com.grab.grazel.migrate.android.DefaultAndroidBinaryDataExtractor
import com.grab.grazel.migrate.android.DefaultCrashlyticsDataExtractor
import com.grab.grazel.migrate.android.DefaultGoogleServicesJsonExtractor
import com.grab.grazel.migrate.android.DefaultKeyStoreExtractor
import com.grab.grazel.migrate.android.DefaultManifestValuesBuilder
import com.grab.grazel.migrate.android.GoogleServicesJsonExtractor
import com.grab.grazel.migrate.android.KeyStoreExtractor
import com.grab.grazel.migrate.android.ManifestValuesBuilder
import com.grab.grazel.migrate.android.toTarget
import com.grab.grazel.migrate.toBazelDependency
import com.grab.grazel.util.GradleProvider
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import org.gradle.api.Project
import javax.inject.Inject
import javax.inject.Singleton

@Module
internal interface AndroidBinaryTargetBuilderModule {
    @Binds
    fun DefaultAndroidBinaryDataExtractor.bindAndroidBinaryDataExtractor(): AndroidBinaryDataExtractor

    @Binds
    fun DefaultKeyStoreExtractor.bindKeyStoreExtractor(): KeyStoreExtractor

    @Binds
    fun DefaultManifestValuesBuilder.bindDefaultManifestValuesBuilder(): ManifestValuesBuilder

    @Binds
    fun DefaultGoogleServicesJsonExtractor.bindGoogleServicesJsonExtractor(): GoogleServicesJsonExtractor

    @Binds
    fun DefaultCrashlyticsDataExtractor.bindCrashlyticsDataExtractor(): CrashlyticsDataExtractor

    @Binds
    @IntoSet
    fun AndroidBinaryTargetBuilder.bindAndroidBinaryTargetBuilder(): TargetBuilder
}

@Singleton
internal class AndroidBinaryTargetBuilder
@Inject
constructor(
    private val androidLibraryDataExtractor: AndroidLibraryDataExtractor,
    private val androidBinaryDataExtractor: AndroidBinaryDataExtractor,
    private val crashlyticsDataExtractor: CrashlyticsDataExtractor,
    private val variantMatcher: VariantMatcher,
    private val dependencyResolutionService: GradleProvider<DefaultDependencyResolutionService>,
    private val workspaceRenderPlanService: GradleProvider<WorkspaceRenderPlanService>,
) : TargetBuilder {

    override fun build(project: Project): List<BazelTarget> =
        selectBinaryData(project).flatMap { selected ->
            val intermediateTargets = mutableListOf<BazelTarget>()
            val crashlyticsDeps = crashlyticsDeps(
                project,
                selected.matchedVariant,
                intermediateTargets
            )
            listOf(
                AndroidBinaryTarget(
                    name = "${selected.binaryData.name}${selected.matchedVariant.nameSuffix}",
                    srcs = selected.libraryData.srcs,
                    deps = selected.libraryData.deps + selected.binaryData.deps + crashlyticsDeps,
                    multidex = selected.binaryData.multidex,
                    debugKey = selected.binaryData.debugKey,
                    dexShards = selected.binaryData.dexShards,
                    incrementalDexing = selected.binaryData.incrementalDexing,
                    enableCompose = selected.binaryData.compose,
                    enableDataBinding = selected.binaryData.databinding,
                    customPackage = selected.binaryData.customPackage,
                    packageName = selected.binaryData.packageName,
                    manifest = selected.libraryData.manifestFile,
                    manifestValues = selected.binaryData.manifestValues,
                    resConfigFilters = selected.binaryData.resConfigs,
                    resourceSets = selected.libraryData.resourceSets,
                    resValuesData = selected.libraryData.resValuesData,
                    buildConfigData = selected.libraryData.buildConfigData,
                    lintConfigData = selected.libraryData.lintConfigData,
                    minSdkVersion = selected.binaryData.minSdkVersion,
                    plugins = selected.libraryData.plugins,
                )
            ) + intermediateTargets
        }

    /**
     * Selects the app variants to generate (reachable, or referenced by an already-rendered
     * target) with their extracted library+binary data. Crashlytics decoration is deliberately
     * NOT selection: it is render-only (intra-package target) and never contributed to
     * reference facts — see AndroidBinaryVariantTargetData's KDoc.
     */
    internal fun selectBinaryData(project: Project): List<AndroidBinaryVariantTargetData> {
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
            AndroidBinaryVariantTargetData(
                matchedVariant = matchedVariant,
                libraryData = androidLibraryDataExtractor.extract(project, matchedVariant),
                binaryData = androidBinaryDataExtractor.extract(project, matchedVariant)
            )
        }
    }

    private fun crashlyticsDeps(
        project: Project,
        matchedVariant: MatchedVariant,
        intermediateTargets: MutableList<BazelTarget>
    ) = if (project.hasGooglePlayServicesPlugin && project.hasCrashlytics) {
        val crashlyticsTarget = crashlyticsDataExtractor.extract(
            project = project,
            matchedVariant = matchedVariant,
        ).toTarget()
        intermediateTargets.add(crashlyticsTarget)
        listOf(crashlyticsTarget.toBazelDependency())
    } else emptyList()

    override fun canHandle(project: Project) = project.isAndroidApplication
}
