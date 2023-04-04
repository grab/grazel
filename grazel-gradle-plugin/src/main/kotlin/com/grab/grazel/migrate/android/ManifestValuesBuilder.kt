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

package com.grab.grazel.migrate.android

import com.android.build.gradle.BaseExtension
import com.android.build.gradle.internal.dsl.DefaultConfig
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.ConfigurationScope.BUILD
import com.grab.grazel.gradle.dependencies.BuildGraphType
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.variant.AndroidVariantDataSource
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.gradle.variant.getMigratableBuildVariants
import dagger.Lazy
import org.gradle.api.Project
import org.gradle.kotlin.dsl.the
import javax.inject.Inject

internal interface ManifestValuesBuilder {
    fun build(
        project: Project,
        matchedVariant: MatchedVariant,
        defaultConfig: DefaultConfig,
        configurationScope: ConfigurationScope = BUILD
    ): Map<String, String?>
}

internal class DefaultManifestValuesBuilder
@Inject
constructor(
    private val dependencyGraphsProvider: Lazy<DependencyGraphs>,
    private val variantDataSource: AndroidVariantDataSource
) : ManifestValuesBuilder {

    private val projectDependencyGraphs get() = dependencyGraphsProvider.get()

    override fun build(
        project: Project,
        matchedVariant: MatchedVariant,
        defaultConfig: DefaultConfig,
        configurationScope: ConfigurationScope
    ): Map<String, String?> {
        // Collect manifest values for all dependant projects
        val libraryFlavorManifestPlaceHolders = projectDependencyGraphs
            .dependenciesSubGraph(
                project,
                BuildGraphType(configurationScope, matchedVariant.variant)
            ).asSequence()
            .filter(Project::isAndroid)
            .flatMap { depProject ->
                val defaultConfigPlaceHolders = depProject.the<BaseExtension>()
                    .defaultConfig
                    .manifestPlaceholders
                    .mapValues { it.value.toString() }
                    .map { it.key to it.value }

                val migratableVariants = variantDataSource
                    .getMigratableBuildVariants(depProject)
                    .asSequence()

                val buildTypePlaceholders = migratableVariants
                    .flatMap { baseVariant ->
                        // TODO This should only include matchedVariant's build type, can be fixed
                        // after migrating projectDependencyGraphs to variant graphs.
                        baseVariant
                            .buildType
                            .manifestPlaceholders
                            .mapValues { it.value.toString() }
                            .map { it.key to it.value }
                            .asSequence()
                    }

                val flavorPlaceHolders: Sequence<Pair<String, String>> = migratableVariants
                    .flatMap { baseVariant -> baseVariant.productFlavors.asSequence() }
                    .flatMap { flavor ->
                        flavor.manifestPlaceholders
                            .map { it.key to it.value.toString() }
                            .asSequence()
                    }
                (defaultConfigPlaceHolders + buildTypePlaceholders + flavorPlaceHolders).asSequence()
            }.toMap()

        // Collect manifest values from current binary target
        val defautConfigPlaceHolders: Map<String, String?> = defaultConfig
            .manifestPlaceholders
            .mapValues { it.value.toString() }

        val variantPlaceholders = matchedVariant.variant
            .mergedFlavor
            .manifestPlaceholders
            .mapValues { it.value.toString() }

        // Android specific values
        val androidManifestValues: Map<String, String?> = mapOf(
            "versionCode" to defaultConfig.versionCode?.toString(),
            "versionName" to defaultConfig.versionName,
            "minSdkVersion" to defaultConfig.minSdkVersion?.apiLevel?.toString(),
            "targetSdkVersion" to defaultConfig.targetSdkVersion?.apiLevel?.toString(),
            "applicationId" to matchedVariant.variant.applicationId
        )
        return (androidManifestValues + defautConfigPlaceHolders + variantPlaceholders + libraryFlavorManifestPlaceHolders)
    }
}

