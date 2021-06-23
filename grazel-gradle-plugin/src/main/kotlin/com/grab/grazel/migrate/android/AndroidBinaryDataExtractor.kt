/*
 * Copyright 2021 Grabtaxi Holdings PTE LTE (GRAB)
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

package com.grab.grazel.migrate.android

import com.android.build.gradle.BaseExtension
import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.rules.DATABINDING_ARTIFACTS
import com.grab.grazel.bazel.rules.Multidex
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.gradle.AndroidBuildVariantDataSource
import com.grab.grazel.gradle.hasCrashlytics
import com.grab.grazel.gradle.hasDatabinding
import com.grab.grazel.gradle.hasGooglePlayServicesPlugin
import org.gradle.api.Project
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency
import org.gradle.kotlin.dsl.getByType
import javax.inject.Inject
import javax.inject.Singleton


internal interface AndroidBinaryDataExtractor {
    fun extract(project: Project, androidLibraryData: AndroidLibraryData): AndroidBinaryData
}

@Singleton
internal class DefaultAndroidBinaryDataExtractor @Inject constructor(
    private val buildVariantDataSource: AndroidBuildVariantDataSource,
    private val grazelExtension: GrazelExtension,
    private val keyStoreExtractor: KeyStoreExtractor,
    private val manifestValuesBuilder: ManifestValuesBuilder
) : AndroidBinaryDataExtractor {

    override fun extract(project: Project, androidLibraryData: AndroidLibraryData): AndroidBinaryData {
        val extension = project.extensions.getByType<BaseExtension>()
        val manifestValues =
            manifestValuesBuilder.build(project, extension.defaultConfig, androidLibraryData.packageName)
        val multidexEnabled = extension.defaultConfig.multiDexEnabled == true
                || grazelExtension.androidConfiguration.multiDexEnabled
        val multidex = if (multidexEnabled) Multidex.Native else Multidex.Off
        val dexShards = if (multidexEnabled) grazelExtension.androidConfiguration.dexShards else null

        val googleServicesJson = if (project.hasGooglePlayServicesPlugin) {
            findGoogleServicesJson(
                variants = buildVariantDataSource.getMigratableVariants(project),
                project = project
            )
        } else null

        val buildId = if (project.hasGooglePlayServicesPlugin && project.hasCrashlytics)
            grazelExtension.rulesConfiguration.googleServices.crashlytics.buildId else null

        val deps = if (project.hasDatabinding) databindingDependencies else emptyList()

        val debugKey = keyStoreExtractor.extract(
            rootProject = project.rootProject,
            variant = buildVariantDataSource.getMigratableVariants(project).firstOrNull()
        )


        return AndroidBinaryData(
            name = project.name,
            manifestValues = manifestValues,
            deps = deps,
            multidex = multidex,
            dexShards = dexShards,
            debugKey = debugKey,
            buildId = buildId,
            googleServicesJson = googleServicesJson,
            hasCrashlytics = project.hasCrashlytics,
            hasDatabinding = project.hasDatabinding
        )
    }

    private val databindingDependencies: List<BazelDependency> = DATABINDING_ARTIFACTS
        .asSequence()
        .filter { it.name != "databinding-compiler" }
        .map {
            BazelDependency.MavenDependency(
                DefaultExternalModuleDependency(
                    it.group!!,
                    it.name!!,
                    it.version!!
                )
            )
        }.toList()
}