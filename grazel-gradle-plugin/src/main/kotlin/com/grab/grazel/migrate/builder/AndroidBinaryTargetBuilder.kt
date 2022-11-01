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
import com.grab.grazel.gradle.dependencies.variantNameSuffix
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.gradle.isKotlin
import com.grab.grazel.migrate.BazelTarget
import com.grab.grazel.migrate.TargetBuilder
import com.grab.grazel.migrate.android.AndroidBinaryDataExtractor
import com.grab.grazel.migrate.android.AndroidBinaryTarget
import com.grab.grazel.migrate.android.AndroidLibraryDataExtractor
import com.grab.grazel.migrate.android.CrashlyticsTarget
import com.grab.grazel.migrate.android.DefaultAndroidBinaryDataExtractor
import com.grab.grazel.migrate.android.DefaultKeyStoreExtractor
import com.grab.grazel.migrate.android.DefaultManifestValuesBuilder
import com.grab.grazel.migrate.android.KeyStoreExtractor
import com.grab.grazel.migrate.android.ManifestValuesBuilder
import com.grab.grazel.migrate.android.SourceSetType
import com.grab.grazel.migrate.android.VariantsMerger
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
    @IntoSet
    fun AndroidBinaryTargetBuilder.bindAndroidBinaryTargetBuilder(): TargetBuilder
}

@Singleton
internal class AndroidBinaryTargetBuilder @Inject constructor(
    private val androidLibDataExtractor: AndroidLibraryDataExtractor,
    private val androidBinDataExtractor: AndroidBinaryDataExtractor,
    private val variantsMerger: VariantsMerger
) : TargetBuilder {

    override fun build(project: Project): List<BazelTarget> {
        val ktAndroidTargets = buildKtAndroidTargets(project)
        return buildAndroidBinaryTargets(project, ktAndroidTargets)
    }

    private fun buildAndroidBinaryTargets(
        project: Project,
        intermediateTargets: List<BazelTarget>
    ): List<BazelTarget> {

        var targets = variantsMerger.merge(project, ConfigurationScope.BUILD)
            .flatMap { mergedVariant ->
                var androidLibData = androidLibDataExtractor.extract(project, mergedVariant)
                val deps = if (project.isKotlin) {
                    // For kotlin project, don't duplicate Maven dependencies
                    intermediateTargets.filter { it.name.endsWith(mergedVariant.variantName.variantNameSuffix()) }
                        .map { it.toBazelDependency() }
                } else {
                    intermediateTargets.filter { it.name.endsWith(mergedVariant.variantName.variantNameSuffix()) }
                        .map { it.toBazelDependency() } + androidLibData.deps
                }

                androidLibData = androidLibData.copy(deps = deps)
                val binaryData =
                    androidBinDataExtractor.extract(project, mergedVariant.variant, androidLibData)

                listOf(
                    AndroidBinaryTarget(
                        name = "${binaryData.name}${mergedVariant.variantName.variantNameSuffix()}",
                        deps = androidLibData.deps + binaryData.deps,
                        srcs = androidLibData.srcs,
                        multidex = binaryData.multidex,
                        debugKey = binaryData.debugKey,
                        dexShards = binaryData.dexShards,
                        incrementalDexing = binaryData.incrementalDexing,
                        enableDataBinding = binaryData.hasDatabinding,
                        packageName = androidLibData.packageName,
                        manifest = androidLibData.manifestFile,
                        manifestValues = binaryData.manifestValues,
                        res = androidLibData.res,
                        resValues = androidLibData.resValues,
                        customResourceSets = androidLibData.extraRes,
                        assetsGlob = androidLibData.assets,
                        assetsDir = androidLibData.assetsDir,
                        buildId = binaryData.buildId,
                        googleServicesJson = binaryData.googleServicesJson,
                        hasCrashlytics = binaryData.hasCrashlytics
                    )
                )
            } + intermediateTargets

        targets = addCrashlyticsTarget(targets)

        return targets
    }

    private fun addCrashlyticsTarget(targets: List<BazelTarget>): List<BazelTarget> {
        var resultTargets = targets
        val androidTargetWithCrashlytics = resultTargets.firstOrNull { bazelTarget ->
            bazelTarget is AndroidBinaryTarget &&
                bazelTarget.hasCrashlytics &&
                bazelTarget.buildId != null &&
                bazelTarget.googleServicesJson != null
        } as AndroidBinaryTarget?
        if (androidTargetWithCrashlytics != null) {
            val crashlyticsTarget = with(androidTargetWithCrashlytics) {
                CrashlyticsTarget(
                    packageName = packageName,
                    buildId = buildId!!,
                    googleServicesJson = googleServicesJson!!
                )
            }

            resultTargets = resultTargets.map { bazelTarget ->
                if (bazelTarget is AndroidBinaryTarget) {
                    bazelTarget.copy(deps = bazelTarget.deps + crashlyticsTarget.toBazelDependency())
                } else {
                    bazelTarget
                }
            }.toMutableList()
            resultTargets.add(0, crashlyticsTarget)
        }
        return resultTargets
    }

    private fun buildKtAndroidTargets(project: Project): List<BazelTarget> {
        return buildList {
            variantsMerger.merge(project, ConfigurationScope.BUILD)
                .forEach { mergedVariant ->
                    val androidProjectData = androidLibDataExtractor.extract(
                        project = project,
                        sourceSetType = SourceSetType.JAVA_KOTLIN,
                        mergedVariant = mergedVariant
                    ).copy(name = "${project.name}_lib", hasDatabinding = false)
                    var deps = androidProjectData.deps

                    with(androidProjectData) {
                        toBuildConfigTarget(mergedVariant.variantName.variantNameSuffix()).also {
                            deps += it.toBazelDependency()
                            add(it)
                        }
                    }

                    androidProjectData
                        .copy(
                            name = "${androidProjectData.name}${mergedVariant.variantName.variantNameSuffix()}",
                            deps = deps,
                            tags = emptyList() // Don't generate classpath reduction tags for final binary target
                        )
                        .toKtLibraryTarget()
                        ?.also { add(it) }
                }
        }
    }

    override fun canHandle(project: Project): Boolean = project.isAndroidApplication
}
