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

import com.grab.grazel.gradle.ConfigurationScope.BUILD
import com.grab.grazel.gradle.hasCrashlytics
import com.grab.grazel.gradle.hasGooglePlayServicesPlugin
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.gradle.variant.VariantMatcher
import com.grab.grazel.gradle.variant.nameSuffix
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
    private val variantMatcher: VariantMatcher
) : TargetBuilder {

    override fun build(project: Project): List<BazelTarget> {
        return buildAndroidBinaryTargets(project = project)
    }

    private fun buildAndroidBinaryTargets(
        project: Project
    ): List<BazelTarget> {
        val targets = variantMatcher.matchedVariants(project, BUILD).flatMap { matchedVariant ->
            val androidLibraryData = androidLibraryDataExtractor.extract(
                project = project,
                matchedVariant = matchedVariant
            )

            val androidBinaryData = androidBinaryDataExtractor.extract(
                project = project,
                matchedVariant = matchedVariant,
            )

            // TODO Implement this via bazel-common
            val intermediateTargets = mutableListOf<BazelTarget>()
            val crashlyticsDeps = crashlyticsDeps(
                project,
                matchedVariant,
                intermediateTargets
            )

            listOf(
                AndroidBinaryTarget(
                    name = "${androidBinaryData.name}${matchedVariant.nameSuffix}",
                    srcs = androidLibraryData.srcs,
                    deps = androidLibraryData.deps + androidBinaryData.deps + crashlyticsDeps,
                    multidex = androidBinaryData.multidex,
                    debugKey = androidBinaryData.debugKey,
                    dexShards = androidBinaryData.dexShards,
                    incrementalDexing = androidBinaryData.incrementalDexing,
                    enableDataBinding = androidBinaryData.databinding,
                    customPackage = androidBinaryData.customPackage,
                    packageName = androidBinaryData.packageName,
                    manifest = androidLibraryData.manifestFile,
                    manifestValues = androidBinaryData.manifestValues,
                    res = androidLibraryData.res,
                    resValuesData = androidLibraryData.resValuesData,
                    customResourceSets = androidLibraryData.extraRes,
                    assetsGlob = androidLibraryData.assets,
                    assetsDir = androidLibraryData.assetsDir,
                    buildConfigData = androidLibraryData.buildConfigData
                )
            ) + intermediateTargets
        }
        return targets
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

    override fun sortOrder(): Int = 1
}
