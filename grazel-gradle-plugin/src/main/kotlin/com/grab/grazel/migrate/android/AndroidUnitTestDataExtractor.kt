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

import com.android.build.gradle.api.AndroidSourceSet
import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.extension.KotlinExtension
import com.grab.grazel.gradle.AndroidVariantDataSource
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.dependencies.BuildGraphType
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.dependencies.GradleDependencyToBazelDependency
import com.grab.grazel.gradle.dependencies.variantNameSuffix
import com.grab.grazel.gradle.getMigratableBuildVariants
import com.grab.grazel.migrate.common.calculateTestAssociate
import com.grab.grazel.migrate.dependencies.calculateDirectDependencyTags
import com.grab.grazel.migrate.kotlin.kotlinParcelizeDeps
import dagger.Lazy
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal const val FORMAT_UNIT_TEST_NAME = "%s%s-test"

internal interface AndroidUnitTestDataExtractor {
    fun extract(project: Project, mergedVariant: MergedVariant): AndroidUnitTestData
}

@Singleton
internal class DefaultAndroidUnitTestDataExtractor @Inject constructor(
    private val dependenciesDataSource: DependenciesDataSource,
    private val variantDataSource: AndroidVariantDataSource,
    private val dependencyGraphsProvider: Lazy<DependencyGraphs>,
    private val androidManifestParser: AndroidManifestParser,
    private val grazelExtension: GrazelExtension,
    private val gradleDependencyToBazelDependency: GradleDependencyToBazelDependency
) : AndroidUnitTestDataExtractor {

    private val projectDependencyGraphs get() = dependencyGraphsProvider.get()

    private val kotlinExtension: KotlinExtension get() = grazelExtension.rules.kotlin

    override fun extract(project: Project, mergedVariant: MergedVariant): AndroidUnitTestData {
        val name = FORMAT_UNIT_TEST_NAME.format(
            project.name,
            mergedVariant.variantName.variantNameSuffix()
        )

        val migratableSourceSets = mergedVariant.variant.sourceSets
            .asSequence()
            .filterIsInstance<AndroidSourceSet>()

        val srcs = project.unitTestSources(migratableSourceSets).toList()
        val additionalSrcSets = project.unitTestNonDefaultSourceSets(migratableSourceSets).toList()

        val resources = project.unitTestResources(migratableSourceSets).toList()

        val associate = calculateTestAssociate(project, targetVariantSuffix(mergedVariant))

        val deps = projectDependencyGraphs
            .directDependencies(
                project,
                BuildGraphType(ConfigurationScope.TEST, mergedVariant.variant)
            ).map { dependent ->
                gradleDependencyToBazelDependency.map(project, dependent, mergedVariant)
            } +
            dependenciesDataSource.collectMavenDeps(
                project,
                BuildGraphType(ConfigurationScope.TEST, mergedVariant.variant)
            ) +
            project.kotlinParcelizeDeps() +
            BazelDependency.ProjectDependency(
                project,
                targetVariantSuffix(mergedVariant)
            )

        val tags = if (kotlinExtension.enabledTransitiveReduction) {
            deps.calculateDirectDependencyTags(name)
        } else emptyList()

        return AndroidUnitTestData(
            name = name,
            srcs = srcs,
            additionalSrcSets = additionalSrcSets,
            deps = deps,
            tags = tags,
            customPackage = extractPackageName(project),
            associates = buildList { associate?.let(::add) },
            resources = resources,
        )
    }

    private fun targetVariantSuffix(variant: MergedVariant) =
        (variant.variantName.variantNameSuffix())

    private fun Project.unitTestSources(
        sourceSets: Sequence<AndroidSourceSet>,
        sourceSetType: SourceSetType = SourceSetType.JAVA_KOTLIN
    ): Sequence<String> {
        val dirs = sourceSets.flatMap { it.java.srcDirs.asSequence() }
        val dirsKotlin = dirs.map { File(it.path.replace("/java", "/kotlin")) }
        return filterSourceSetPaths(dirs + dirsKotlin, sourceSetType.patterns)
    }

    private fun Project.unitTestNonDefaultSourceSets(
        sourceSets: Sequence<AndroidSourceSet>,
    ): Sequence<String> {
        val dirs = sourceSets.flatMap { it.java.srcDirs.asSequence() }
        val dirsKotlin = dirs.map { File(it.path.replace("/java", "/kotlin")) }
        return filterNonDefaultSourceSetDirs(dirs + dirsKotlin)
    }

    private fun extractPackageName(project: Project): String {
        val migratableSourceSets = variantDataSource
            .getMigratableBuildVariants(project)
            .asSequence()
            .flatMap { it.sourceSets.asSequence() }
            .filterIsInstance<AndroidSourceSet>()
            .toList()

        return androidManifestParser.parsePackageName(
            project.extensions.getByType(),
            migratableSourceSets
        ) ?: ""
    }
}

internal fun Project.unitTestResources(
    sourceSets: Sequence<AndroidSourceSet>,
    sourceSetType: SourceSetType = SourceSetType.RESOURCES
): Sequence<String> {
    val dirs = sourceSets.flatMap { it.resources.srcDirs.asSequence() }
    val dirsKotlin = dirs.map { File(it.path.replace("/java", "/kotlin")) }
    return filterSourceSetPaths(dirs + dirsKotlin, sourceSetType.patterns)
}
