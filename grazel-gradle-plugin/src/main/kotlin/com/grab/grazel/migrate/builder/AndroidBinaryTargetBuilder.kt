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

package com.grab.grazel.migrate.builder

import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.hasCrashlytics
import com.grab.grazel.gradle.hasGooglePlayServicesPlugin
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.gradle.isKotlin
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.gradle.variant.VariantMatcher
import com.grab.grazel.gradle.variant.nameSuffix
import com.grab.grazel.migrate.BazelTarget
import com.grab.grazel.migrate.TargetBuilder
import com.grab.grazel.migrate.android.AndroidBinaryDataExtractor
import com.grab.grazel.migrate.android.AndroidBinaryTarget
import com.grab.grazel.migrate.android.AndroidLibraryData
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
import com.grab.grazel.migrate.android.SourceSetType
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
    private val androidLibDataExtractor: AndroidLibraryDataExtractor,
    private val androidBinDataExtractor: AndroidBinaryDataExtractor,
    private val crashlyticsDataExtractor: CrashlyticsDataExtractor,
    private val variantMatcher: VariantMatcher
) : TargetBuilder {

    override fun build(project: Project): List<BazelTarget> {
        return buildAndroidBinaryTargets(project = project)
    }

    private fun buildAndroidBinaryTargets(
        project: Project
    ): List<BazelTarget> {
        val targets = variantMatcher.matchedVariants(project, ConfigurationScope.BUILD)
            .flatMap { matchedVariant ->
                val intermediateTargets = buildIntermediateTargets(
                    project = project,
                    matchedVariant = matchedVariant
                ).toMutableList()

                val androidLibData = androidLibDataExtractor.extract(
                    project = project,
                    matchedVariant = matchedVariant
                ).let { libData ->
                    libData.copy(
                        deps = calcDeps(project, intermediateTargets, matchedVariant, libData)
                    )
                }

                val binaryData = androidBinDataExtractor.extract(
                    project,
                    matchedVariant,
                    androidLibData
                )

                val crashlyticsDeps = crashlyticsDeps(
                    project,
                    matchedVariant,
                    intermediateTargets
                )

                listOf(
                    AndroidBinaryTarget(
                        name = "${binaryData.name}${matchedVariant.nameSuffix}",
                        deps = androidLibData.deps + binaryData.deps + crashlyticsDeps,
                        srcs = androidLibData.srcs,
                        multidex = binaryData.multidex,
                        debugKey = binaryData.debugKey,
                        dexShards = binaryData.dexShards,
                        incrementalDexing = binaryData.incrementalDexing,
                        enableDataBinding = binaryData.hasDatabinding,
                        packageName = matchedVariant.variant.applicationId,
                        manifest = androidLibData.manifestFile,
                        manifestValues = binaryData.manifestValues,
                        res = androidLibData.res,
                        customResourceSets = androidLibData.extraRes,
                        assetsGlob = androidLibData.assets,
                        assetsDir = androidLibData.assetsDir,
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

    private fun calcDeps(
        project: Project,
        intermediateTargets: MutableList<BazelTarget>,
        matchedVariant: MatchedVariant,
        androidLibData: AndroidLibraryData
    ) = when {
        project.isKotlin -> {
            // For kotlin project, don't duplicate Maven dependencies
            intermediateTargets
                .filter { it.name.endsWith(matchedVariant.nameSuffix) }
                .map { it.toBazelDependency() }
        }
        else -> intermediateTargets
            .filter { it.name.endsWith(matchedVariant.nameSuffix) }
            .map { it.toBazelDependency() } + androidLibData.deps
    }

    private fun buildIntermediateTargets(
        project: Project,
        matchedVariant: MatchedVariant
    ): List<BazelTarget> = buildList {
        val androidProjectData = androidLibDataExtractor.extract(
            project = project,
            sourceSetType = SourceSetType.JAVA_KOTLIN,
            matchedVariant = matchedVariant
        ).copy(name = "${project.name}_lib", hasDatabinding = false)

        val deps = androidProjectData.deps.toMutableList()

        with(androidProjectData) {
            toBuildConfigTarget(matchedVariant.nameSuffix).also {
                deps += it.toBazelDependency()
                add(it)
            }
        }
        androidProjectData
            .copy(
                name = "${androidProjectData.name}${matchedVariant.nameSuffix}",
                deps = deps,
                tags = emptyList() // Don't generate classpath reduction tags for final binary target
            ).toKtLibraryTarget()
            ?.also { add(it) }
    }

    override fun canHandle(project: Project) = project.isAndroidApplication

    override fun sortOrder(): Int = 1
}
