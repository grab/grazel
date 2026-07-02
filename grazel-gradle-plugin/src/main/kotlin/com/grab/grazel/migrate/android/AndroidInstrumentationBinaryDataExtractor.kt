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
import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.gradle.dependencies.DefaultDependencyGraphsService
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.dependencies.GradleDependencyToBazelDependency
import com.grab.grazel.gradle.dependencies.TargetTagKinds
import com.grab.grazel.gradle.dependencies.WorkspaceTargetTagPlanService
import com.grab.grazel.gradle.variant.VariantGraphKey
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.hasCompose
import com.grab.grazel.gradle.variant.AndroidVariantDataSource
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.gradle.variant.getMigratableBuildVariants
import com.grab.grazel.gradle.variant.nameSuffix
import com.grab.grazel.migrate.dependencies.calculateDirectDependencyTags
import com.grab.grazel.util.GradleProvider
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
    private val dependencyGraphsService: GradleProvider<DefaultDependencyGraphsService>,
    private val gradleDependencyToBazelDependency: GradleDependencyToBazelDependency,
    private val androidManifestParser: AndroidManifestParser,
    private val manifestValuesBuilder: ManifestValuesBuilder,
    private val keyStoreExtractor: KeyStoreExtractor,
    private val grazelExtension: GrazelExtension,
    private val workspaceTargetTagPlanService: GradleProvider<WorkspaceTargetTagPlanService>,
) : AndroidInstrumentationBinaryDataExtractor {
    private val projectDependencyGraphs: DependencyGraphs get() = dependencyGraphsService.get().get()

    override fun extract(
        project: Project,
        matchedVariant: MatchedVariant,
        sourceSetType: SourceSetType,
    ): AndroidInstrumentationBinaryData {
        val extension = project.extensions.getByType<BaseExtension>()
        val variantKey = VariantGraphKey.from(project, matchedVariant, VariantType.AndroidTest)
        val deps = projectDependencyGraphs
            .directDependenciesByVariant(
                project,
                variantKey
            ).map { dependency ->
                gradleDependencyToBazelDependency.map(project, dependency, matchedVariant)
            } +
            dependenciesDataSource.collectMavenDeps(
                project,
                variantKey,
                preferredVariantNames = listOf(matchedVariant.variantName)
            ) +
            BazelDependency.ProjectDependency(
                prefix = "lib_",
                dependencyProject = project,
                suffix = matchedVariant.nameSuffix
            )

        return extractAndroidInstrumentationBinaryData(
            project = project,
            matchedVariant = matchedVariant,
            extension = extension,
            deps = deps,
            sourceSetType = sourceSetType,
        )
    }

    private fun extractAndroidInstrumentationBinaryData(
        project: Project,
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
            variantType = VariantType.AndroidTest
        )

        val customPackage = androidManifestParser.parsePackageName(
            extension,
            migratableSourceSets
        ) ?: ""

        val debugKey = keyStoreExtractor.extract(
            rootProject = project.rootProject,
            variant = variantDataSource.getMigratableBuildVariants(project).firstOrNull()
        )

        val associate = BazelDependency.ProjectDependency(
            dependencyProject = project,
            prefix = "lib_",
            suffix = "${matchedVariant.nameSuffix}_kt"
        )

        val resources = unitTestResources(project, migratableSourceSets.asSequence()).toList()
        val resourceStripPrefix = resourceStripPrefix(
            project = project,
            sourceSets = migratableSourceSets.asSequence()
        )
        val resourceFiles = project.androidSources(migratableSourceSets, SourceSetType.RESOURCES).toList()

        val srcs = project.androidSources(migratableSourceSets, sourceSetType).toList()
        val testInstrumentationRunner = extension.extractTestInstrumentationRunner()

        // Bazel 8 compatibility requires omitting minSdk unless the workaround is enabled.
        val minSdkVersion = if (grazelExtension.experiments.minSdkVersionWorkaround.get()) 0 else null
        val tags = if (grazelExtension.rules.kotlin.enabledTransitiveReduction) {
            val variantKey = VariantGraphKey.from(project, matchedVariant, VariantType.AndroidTest)
            val localTags = calculateDirectDependencyTags(
                self = "${project.name}${matchedVariant.nameSuffix}-android-test",
                deps = deps
            )
            val mavenTags = workspaceTargetTagPlanService
                .get()
                .tagsFor(
                    variantId = variantKey.variantId,
                    variantType = variantKey.variantType.toString(),
                    targetKind = TargetTagKinds.ANDROID_INSTRUMENTATION
                )
                .orEmpty()
            (localTags + mavenTags).sorted()
        } else emptyList()

        return AndroidInstrumentationBinaryData(
            name = "${project.name}${matchedVariant.nameSuffix}-android-test",
            associates = listOf(associate),
            customPackage = customPackage,
            targetPackage = matchedVariant.variant.applicationId.split(".test").first(),
            debugKey = debugKey,
            deps = deps.sorted(),
            instruments = BazelDependency.StringDependency(
                ":${project.name}${matchedVariant.nameSuffix}"
            ),
            resources = resources,
            resourceStripPrefix = resourceStripPrefix,
            resourceFiles = resourceFiles,
            srcs = srcs,
            testInstrumentationRunner = testInstrumentationRunner,
            manifestValues = manifestValues,
            tags = tags.sorted(),
            compose = project.hasCompose,
            minSdkVersion = minSdkVersion,
        )
    }
}

internal fun BaseExtension.extractTestInstrumentationRunner(): String? =
    defaultConfig.testInstrumentationRunner

internal fun resourceStripPrefix(
    project: Project,
    sourceSets: Sequence<AndroidSourceSet>,
): String? = sourceSets
    .flatMap { sourceSet ->
        sourceSet.resources.srcDirs.asSequence()
    }
    .filter(File::exists)
    .map(project::relativePath)
    .map { dir ->
        "${project.name}/$dir"
    }
    .distinct()
    .firstOrNull()
