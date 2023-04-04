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
import com.android.build.gradle.api.AndroidSourceSet
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.dependencies.BuildGraphType
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.dependencies.GradleDependencyToBazelDependency
import com.grab.grazel.gradle.variant.AndroidVariantDataSource
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.gradle.variant.getMigratableBuildVariants
import com.grab.grazel.gradle.variant.nameSuffix
import dagger.Lazy
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal interface AndroidInstrumentationBinaryDataExtractor {
    fun extract(
        project: Project,
        matchedVariant: MatchedVariant,
        sourceSetType: SourceSetType = SourceSetType.JAVA,
    ): AndroidInstrumentationBinaryData
}

@Singleton
internal class DefaultAndroidInstrumentationBinaryDataExtractor
@Inject constructor(
    private val variantDataSource: AndroidVariantDataSource,
    private val dependenciesDataSource: DependenciesDataSource,
    private val dependencyGraphsProvider: Lazy<DependencyGraphs>,
    private val gradleDependencyToBazelDependency: GradleDependencyToBazelDependency,
    private val androidManifestParser: AndroidManifestParser,
    private val manifestValuesBuilder: ManifestValuesBuilder,
    private val keyStoreExtractor: KeyStoreExtractor,
) : AndroidInstrumentationBinaryDataExtractor {
    private val projectDependencyGraphs get() = dependencyGraphsProvider.get()

    override fun extract(
        project: Project,
        matchedVariant: MatchedVariant,
        sourceSetType: SourceSetType,
    ): AndroidInstrumentationBinaryData {
        val extension = project.extensions.getByType<BaseExtension>()
        val deps = projectDependencyGraphs
            .directDependencies(
                project,
                BuildGraphType(ConfigurationScope.ANDROID_TEST, matchedVariant.variant)
            ).map { dependency ->
                gradleDependencyToBazelDependency.map(project, dependency, matchedVariant)
            } +
            dependenciesDataSource.collectMavenDeps(
                project,
                BuildGraphType(ConfigurationScope.ANDROID_TEST, matchedVariant.variant)
            ) +
            BazelDependency.ProjectDependency(
                prefix = "lib_",
                dependencyProject = project,
                suffix = "${matchedVariant.nameSuffix}"
            )

        return project.extract(
            matchedVariant = matchedVariant,
            extension = extension,
            deps = deps,
            sourceSetType = sourceSetType,
        )
    }

    private fun Project.extract(
        matchedVariant: MatchedVariant,
        extension: BaseExtension,
        deps: List<BazelDependency>,
        sourceSetType: SourceSetType,
    ): AndroidInstrumentationBinaryData {

        val migratableSourceSets = matchedVariant.variant.sourceSets
            .filterIsInstance<AndroidSourceSet>()
            .toList()

        val manifestValues = manifestValuesBuilder.build(
            project = project,
            matchedVariant = matchedVariant,
            defaultConfig = extension.defaultConfig,
            configurationScope = ConfigurationScope.ANDROID_TEST
        )

        val customPackage = androidManifestParser.parsePackageName(
            extension,
            migratableSourceSets
        ) ?: ""

        val debugKey = keyStoreExtractor.extract(
            rootProject = rootProject,
            variant = variantDataSource.getMigratableBuildVariants(this).firstOrNull()
        )

        val associate = BazelDependency.ProjectDependency(
            dependencyProject = this,
            prefix = "lib_",
            suffix = "${matchedVariant.nameSuffix}_kt"
        )

        val resources = unitTestResources(migratableSourceSets.asSequence()).toList()
        val resourceStripPrefix = resourceStripPrefix(migratableSourceSets.asSequence())
        val resourceFiles = androidSources(migratableSourceSets, SourceSetType.RESOURCES).toList()

        val srcs = androidSources(migratableSourceSets, sourceSetType).toList()
        val testInstrumentationRunner = extension.extractTestInstrumentationRunner()

        return AndroidInstrumentationBinaryData(
            name = "${name}${matchedVariant.nameSuffix}-android-test",
            associates = listOf(associate),
            customPackage = customPackage,
            targetPackage = matchedVariant.variant.applicationId.split(".test").first(),
            debugKey = debugKey,
            deps = deps,
            instruments = BazelDependency.StringDependency(
                ":${name}${matchedVariant.nameSuffix}"
            ),
            resources = resources,
            resourceStripPrefix = resourceStripPrefix,
            resourceFiles = resourceFiles,
            srcs = srcs,
            testInstrumentationRunner = testInstrumentationRunner,
            manifestValues = manifestValues,
        )
    }
}

internal fun BaseExtension.extractTestInstrumentationRunner(): String? =
    defaultConfig.testInstrumentationRunner

internal fun Project.resourceStripPrefix(
    sourceSets: Sequence<AndroidSourceSet>,
): String? = sourceSets
    .flatMap { sourceSet ->
        sourceSet.resources.srcDirs.asSequence()
    }
    .filter(File::exists)
    .map(::relativePath)
    .map { dir ->
        "$name/$dir"
    }
    .distinct()
    .firstOrNull()
