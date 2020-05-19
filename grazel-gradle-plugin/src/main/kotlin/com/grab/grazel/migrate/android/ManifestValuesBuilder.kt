/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate.android

import com.android.build.gradle.BaseExtension
import com.android.build.gradle.internal.dsl.DefaultConfig
import com.google.common.graph.ImmutableValueGraph
import com.grab.grazel.gradle.AndroidBuildVariantDataSource
import com.grab.grazel.gradle.dependenciesSubGraph
import com.grab.grazel.gradle.isAndroid
import dagger.Lazy
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.kotlin.dsl.the
import javax.inject.Inject

internal interface ManifestValuesBuilder {
    fun build(project: Project, defaultConfig: DefaultConfig, packageName: String): Map<String, String?>
}

internal class DefaultManifestValuesBuilder @Inject constructor(
    private val dependencyGraphProvider: Lazy<ImmutableValueGraph<Project, Configuration>>,
    private val buildVariantDataSource: AndroidBuildVariantDataSource
) : ManifestValuesBuilder {
    private val projectDependencyGraph get() = dependencyGraphProvider.get()
    override fun build(
        project: Project,
        defaultConfig: DefaultConfig,
        packageName: String
    ): Map<String, String?> {
        // Collect manifest values for all dependant projects
        val libraryFlavorManifestPlaceHolders = project
            .dependenciesSubGraph(projectDependencyGraph)
            .asSequence()
            .filter(Project::isAndroid)
            .flatMap { depProject ->
                val defaultConfigPlaceHolders = depProject.the<BaseExtension>()
                    .defaultConfig
                    .manifestPlaceholders
                    .mapValues { it.value.toString() }
                    .map { it.key to it.value }

                val migratableVariants = buildVariantDataSource
                    .getMigratableVariants(depProject)
                    .asSequence()

                val buildTypePlaceholders = migratableVariants
                    .flatMap { baseVariant ->
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

        // Android specific values
        val androidManifestValues: Map<String, String?> = mapOf(
            "versionCode" to defaultConfig.versionCode?.toString(),
            "versionName" to defaultConfig.versionName?.toString(),
            "minSdkVersion" to defaultConfig.minSdkVersion?.apiLevel?.toString(),
            "targetSdkVersion" to defaultConfig.targetSdkVersion?.apiLevel?.toString(),
            "applicationId" to packageName
        )
        return (androidManifestValues + defautConfigPlaceHolders + libraryFlavorManifestPlaceHolders)
    }
}

