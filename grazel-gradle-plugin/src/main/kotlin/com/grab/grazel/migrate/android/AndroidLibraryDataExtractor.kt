/*
 * Copyright 2021 Grabtaxi Holdings PTE LTD (GRAB)
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
import com.grab.grazel.bazel.rules.ANDROIDX_GROUP
import com.grab.grazel.bazel.rules.ANNOTATION_ARTIFACT
import com.grab.grazel.bazel.rules.DAGGER_GROUP
import com.grab.grazel.bazel.rules.DATABINDING_GROUP
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.gradle.AndroidVariantDataSource
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.dependencies.directProjectDependencies
import com.grab.grazel.gradle.getMigratableBuildVariants
import com.grab.grazel.gradle.hasDatabinding
import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.migrate.dependencies.calculateDirectDependencyTags
import com.grab.grazel.migrate.kotlin.kotlinParcelizeDeps
import dagger.Lazy
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal interface AndroidLibraryDataExtractor {
    fun extract(
        project: Project,
        sourceSetType: SourceSetType = SourceSetType.JAVA
    ): AndroidLibraryData
}

@Singleton
internal class DefaultAndroidLibraryDataExtractor @Inject constructor(
    private val variantDataSource: AndroidVariantDataSource,
    private val dependenciesDataSource: DependenciesDataSource,
    private val dependencyGraphsProvider: Lazy<DependencyGraphs>,
    private val androidManifestParser: AndroidManifestParser,
    private val grazelExtension: GrazelExtension,
) : AndroidLibraryDataExtractor {
    private val projectDependencyGraphs get() = dependencyGraphsProvider.get()

    override fun extract(project: Project, sourceSetType: SourceSetType): AndroidLibraryData {
        if (project.isAndroid) {
            val extension = project.extensions.getByType<BaseExtension>()
            val deps = projectDependencyGraphs
                .directProjectDependencies(project, ConfigurationScope.BUILD) +
                dependenciesDataSource.collectMavenDeps(project) +
                project.kotlinParcelizeDeps()

            return project.extract(extension, sourceSetType, deps)
        } else {
            throw IllegalArgumentException("${project.name} is not an Android project")
        }
    }

    private fun Project.extract(
        extension: BaseExtension,
        sourceSetType: SourceSetType = SourceSetType.JAVA,
        deps: List<BazelDependency>
    ): AndroidLibraryData {
        // Only consider source sets from migratable variants
        val migratableSourceSets = variantDataSource
            .getMigratableBuildVariants(this)
            .asSequence()
            .flatMap { it.sourceSets.asSequence() }
            .filterIsInstance<AndroidSourceSet>()
            .toList()

        val packageName = androidManifestParser.parsePackageName(
            extension,
            migratableSourceSets
        ) ?: ""
        val srcs = androidSources(migratableSourceSets, sourceSetType).toList()
        val res = androidSources(migratableSourceSets, SourceSetType.RESOURCES).toList()

        // Handle custom Gradle source sets
        val additionalRes = androidSources(
            migratableSourceSets,
            SourceSetType.RESOURCES_CUSTOM
        ).toList()
        val extraRes = getExtraRes(migratableSourceSets, additionalRes)
        val assets = androidSources(migratableSourceSets, SourceSetType.ASSETS).toList()
        val assetsDir = assetsDirectory(migratableSourceSets, assets)
        val manifestFile = androidManifestParser
            .androidManifestFile(migratableSourceSets)
            ?.let(::relativePath)

        val tags = if (grazelExtension.rules.kotlin.enabledTransitiveReduction) {
            deps.calculateDirectDependencyTags(name)
        } else emptyList()

        return AndroidLibraryData(
            name = name,
            srcs = srcs,
            res = res,
            assets = assets,
            assetsDir = assetsDir,
            manifestFile = manifestFile,
            packageName = packageName,
            hasDatabinding = project.hasDatabinding,
            buildConfigData = extension.extractBuildConfig(this, variantDataSource),
            resValues = extension.extractResValue(),
            extraRes = extraRes,
            deps = deps,
            tags = tags
        )
    }

    private fun Project.getExtraRes(
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


    private fun Project.androidSources(
        sourceSets: List<AndroidSourceSet>,
        sourceSetType: SourceSetType
    ): Sequence<String> {
        val sourceSetChoosers: AndroidSourceSet.() -> Sequence<File> =
            when (sourceSetType) {
                SourceSetType.JAVA, SourceSetType.JAVA_KOTLIN, SourceSetType.KOTLIN -> {
                    { java.srcDirs.asSequence() }
                }
                SourceSetType.RESOURCES -> {
                    {
                        res.srcDirs
                            .asSequence()
                            .filter { it.endsWith("res") } // Filter all custom resource sets
                    }
                }
                SourceSetType.RESOURCES_CUSTOM -> {
                    {
                        res.srcDirs
                            .asSequence()
                            .filter { !it.endsWith("res") } // Filter all standard resource sets
                    }
                }
                SourceSetType.ASSETS -> {
                    {
                        assets.srcDirs
                            .asSequence()
                            .filter { it.endsWith("assets") } // Filter all custom resource sets
                    }
                }
            }
        val dirs = sourceSets.asSequence().flatMap(sourceSetChoosers)
        val dirsKotlin = dirs
            .map { File(it.path.replace("/java", "/kotlin")) } //TODO(arun) Remove hardcoding
        return filterSourceSetPaths(dirs + dirsKotlin, sourceSetType.patterns)
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

internal fun DependenciesDataSource.collectMavenDeps(
    project: Project, vararg scopes: ConfigurationScope
): List<BazelDependency> = mavenDependencies(project, *scopes)
    .filter {
        if (project.hasDatabinding) {
            it.group != DATABINDING_GROUP && (it.group != ANDROIDX_GROUP && it.name != ANNOTATION_ARTIFACT)
        } else true
    }.map {
        if (it.group == DAGGER_GROUP) {
            BazelDependency.StringDependency("//:dagger")
        } else {
            BazelDependency.MavenDependency(it)
        }
    }.distinct()
    .toList()
