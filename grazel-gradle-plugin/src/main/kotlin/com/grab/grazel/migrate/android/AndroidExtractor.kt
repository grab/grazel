/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
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
import com.android.build.gradle.api.AndroidSourceSet
import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.rules.Multidex
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.gradle.ConfigurationScope.BUILD
import com.grab.grazel.gradle.dependencies.BuildGraphType
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.dependencies.GradleDependencyToBazelDependency
import com.grab.grazel.gradle.hasCrashlytics
import com.grab.grazel.gradle.hasDatabinding
import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.variant.AndroidVariantDataSource
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.gradle.variant.getMigratableBuildVariants
import com.grab.grazel.gradle.variant.nameSuffix
import com.grab.grazel.migrate.android.SourceSetType.ASSETS
import com.grab.grazel.migrate.android.SourceSetType.JAVA_KOTLIN
import com.grab.grazel.migrate.android.SourceSetType.RESOURCES
import com.grab.grazel.migrate.android.SourceSetType.RESOURCES_CUSTOM
import com.grab.grazel.migrate.dependencies.calculateDirectDependencyTags
import com.grab.grazel.migrate.kotlin.kotlinParcelizeDeps
import dagger.Lazy
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import javax.inject.Inject
import javax.inject.Singleton

internal interface AndroidExtractor<T : AndroidData> {
    fun extract(
        project: Project,
        matchedVariant: MatchedVariant,
    ): T
}

internal interface AndroidLibraryDataExtractor : AndroidExtractor<AndroidLibraryData>

@Singleton
internal class DefaultAndroidLibraryDataExtractor
@Inject
constructor(
    private val androidManifestParser: AndroidManifestParser,
    private val grazelExtension: GrazelExtension,
    private val dependenciesDataSource: DependenciesDataSource,
    private val dependencyGraphsProvider: Lazy<DependencyGraphs>,
    private val gradleDependencyToBazelDependency: GradleDependencyToBazelDependency
) : AndroidLibraryDataExtractor {

    private val projectDependencyGraphs get() = dependencyGraphsProvider.get()

    override fun extract(
        project: Project,
        matchedVariant: MatchedVariant
    ): AndroidLibraryData {
        when {
            project.isAndroid -> {
                val extension = project.extensions.getByType<BaseExtension>()

                val deps: List<BazelDependency> = projectDependencyGraphs.directDependencies(
                    project = project,
                    buildGraphType = BuildGraphType(
                        configurationScope = BUILD,
                        variant = matchedVariant.variant
                    )
                ).map { dependent ->
                    gradleDependencyToBazelDependency.map(
                        project,
                        dependent,
                        matchedVariant
                    )
                } + dependenciesDataSource.collectMavenDeps(
                    project,
                    BuildGraphType(BUILD, matchedVariant.variant)
                ) + project.kotlinParcelizeDeps()
                return project.extract(matchedVariant, extension, deps)
            }

            else -> throw IllegalArgumentException("${project.name} is not an Android project")
        }
    }

    private fun Project.extract(
        matchedVariant: MatchedVariant,
        extension: BaseExtension,
        deps: List<BazelDependency>,
        sourceSetType: SourceSetType = JAVA_KOTLIN
    ): AndroidLibraryData {
        // Only consider source sets from migratable variants
        val migratableSourceSets = matchedVariant.variant.sourceSets
            .filterIsInstance<AndroidSourceSet>()
            .toList()
        val packageName = androidManifestParser.parsePackageName(
            extension,
            migratableSourceSets
        ) ?: ""
        val srcs = androidSources(migratableSourceSets, sourceSetType).toList()
        val res = androidSources(migratableSourceSets, RESOURCES).toList()
        // Handle custom Gradle source sets
        val additionalRes = androidSources(migratableSourceSets, RESOURCES_CUSTOM).toList()
        val extraRes = extractExtraRes(migratableSourceSets, additionalRes)

        val assets = androidSources(migratableSourceSets, ASSETS).toList()
        val assetsDir = assetsDirectory(migratableSourceSets, assets)

        val manifestFile = androidManifestParser.androidManifestFile(migratableSourceSets)
            ?.let(::relativePath)

        val tags = if (grazelExtension.rules.kotlin.enabledTransitiveReduction) {
            deps.calculateDirectDependencyTags(name)
        } else emptyList()

        return AndroidLibraryData(
            name = name + matchedVariant.nameSuffix,
            srcs = srcs,
            res = res,
            assets = assets,
            assetsDir = assetsDir,
            manifestFile = manifestFile,
            customPackage = packageName,
            packageName = packageName,
            databinding = project.hasDatabinding,
            buildConfigData = extension.extractBuildConfig(this, matchedVariant.variant),
            resValuesData = extension.extractResValue(matchedVariant),
            extraRes = extraRes,
            deps = deps,
            tags = tags
        )
    }

    private fun Project.extractExtraRes(
        migratableSourceSets: List<AndroidSourceSet>,
        additionalRes: List<String>
    ): List<ResourceSet> {
        // Get the raw resource directories as declared in the extension
        val allResourceDirs = migratableSourceSets.filter { it.res.srcDirs.size > 1 }
            .flatMap { it.res.srcDirs }
            .map(::relativePath)
        return additionalRes.map { additionalResources ->
            // Find the source set which defines this custom resource set
            val sourceSet = allResourceDirs.first { additionalResources.contains(it) }
            ResourceSet(
                folderName = sourceSet.split("/").last(),
                entry = additionalResources
            )
        }
    }

    private fun Project.assetsDirectory(
        sourceSets: List<AndroidSourceSet>,
        assets: List<String>
    ): String? {
        return if (assets.isNotEmpty()) {
            val assetItem = assets.first()
            sourceSets
                .flatMap { it.assets.srcDirs }
                .map { relativePath(it) }
                .first { assetItem.contains(it) }
        } else null
    }
}

internal interface AndroidBinaryDataExtractor : AndroidExtractor<AndroidBinaryData>

@Singleton
internal class DefaultAndroidBinaryDataExtractor
@Inject
constructor(
    private val variantDataSource: AndroidVariantDataSource,
    private val grazelExtension: GrazelExtension,
    private val keyStoreExtractor: KeyStoreExtractor,
    private val manifestValuesBuilder: ManifestValuesBuilder,
    private val androidManifestParser: AndroidManifestParser,
) : AndroidBinaryDataExtractor {

    override fun extract(project: Project, matchedVariant: MatchedVariant): AndroidBinaryData {
        val extension = project.extensions.getByType<BaseExtension>()
        val manifestValues = manifestValuesBuilder.build(
            project,
            matchedVariant,
            extension.defaultConfig,
        )
        val multidexEnabled = extension.defaultConfig.multiDexEnabled == true
            || grazelExtension.android.multiDexEnabled
        val multidex = if (multidexEnabled) Multidex.Native else Multidex.Off
        val dexShards = if (multidexEnabled) grazelExtension.android.dexShards else null

        val debugKey = keyStoreExtractor.extract(
            rootProject = project.rootProject,
            variant = variantDataSource.getMigratableBuildVariants(project).firstOrNull()
        )

        val customPackage = androidManifestParser.parsePackageName(
            extension = extension,
            androidSourceSets = matchedVariant.variant.sourceSets
                .filterIsInstance<AndroidSourceSet>()
                .toList()
        ) ?: ""

        return AndroidBinaryData(
            name = project.name,
            manifestValues = manifestValues,
            deps = emptyList(),
            multidex = multidex,
            dexShards = dexShards,
            incrementalDexing = grazelExtension.android.incrementalDexing,
            debugKey = debugKey,
            customPackage = customPackage,
            packageName = matchedVariant.variant.applicationId,
            hasCrashlytics = project.hasCrashlytics,
            databinding = project.hasDatabinding
        )
    }
}