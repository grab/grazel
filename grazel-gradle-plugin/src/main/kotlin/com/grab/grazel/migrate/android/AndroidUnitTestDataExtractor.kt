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

import com.android.build.gradle.api.AndroidSourceSet
import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.extension.KotlinExtension
import com.grab.grazel.gradle.dependencies.DefaultDependencyGraphsService
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.dependencies.GradleDependencyToBazelDependency
import com.grab.grazel.gradle.variant.VariantGraphKey
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.hasCompose
import com.grab.grazel.gradle.variant.AndroidVariantDataSource
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.gradle.variant.getMigratableBuildVariants
import com.grab.grazel.gradle.variant.nameSuffix
import com.grab.grazel.migrate.android.SourceSetType.JAVA_KOTLIN
import com.grab.grazel.migrate.common.TestSizeCalculator
import com.grab.grazel.migrate.common.calculateTestAssociates
import com.grab.grazel.migrate.dependencies.calculateDirectDependencyTags
import com.grab.grazel.migrate.kotlin.kotlinParcelizeDeps
import com.grab.grazel.util.GradleProvider
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal const val FORMAT_UNIT_TEST_NAME = "%s%s-test"

internal interface AndroidUnitTestDataExtractor {
    fun extract(project: Project, matchedVariant: MatchedVariant): AndroidUnitTestData
}

@Singleton
internal class DefaultAndroidUnitTestDataExtractor
@Inject
constructor(
    private val dependenciesDataSource: DependenciesDataSource,
    private val dependencyGraphsService: GradleProvider<DefaultDependencyGraphsService>,
    private val androidManifestParser: AndroidManifestParser,
    private val grazelExtension: GrazelExtension,
    private val variantDataSource: AndroidVariantDataSource,
    private val gradleDependencyToBazelDependency: GradleDependencyToBazelDependency,
    private val testSizeCalculator: TestSizeCalculator,
) : AndroidUnitTestDataExtractor {
    private val projectDependencyGraphs: DependencyGraphs get() = dependencyGraphsService.get().get()
    private val kotlinExtension: KotlinExtension get() = grazelExtension.rules.kotlin

    override fun extract(project: Project, matchedVariant: MatchedVariant): AndroidUnitTestData {
        val name = FORMAT_UNIT_TEST_NAME.format(
            project.name,
            matchedVariant.nameSuffix
        )
        val migratableSourceSets = matchedVariant.variant.sourceSets
            .asSequence()
            .filterIsInstance<AndroidSourceSet>()

        val rawSrcs = unitTestSources(migratableSourceSets)
        val srcs = rawSrcs
            .let { srcs -> project.filterSourceSetPaths(srcs, JAVA_KOTLIN.patterns) }
            .toList()
        val packageName = extractPackageName(project)

        val additionalSrcSets = rawSrcs
            .let(project::filterNonDefaultSourceSetDirs)
            .toList()

        val testSize = testSizeCalculator.calculate(name, rawSrcs.toSet())

        val resources = project.unitTestResources(migratableSourceSets).toList()
        val associate = calculateTestAssociates(project, matchedVariant.nameSuffix)

        val variantKey = VariantGraphKey.from(project, matchedVariant, VariantType.Test)
        val deps = projectDependencyGraphs
            .directDependenciesByVariant(
                project = project,
                variantKey = variantKey
            ).map { dependent ->
                gradleDependencyToBazelDependency.map(project, dependent, matchedVariant)
            } +
            dependenciesDataSource.collectMavenDeps(
                project = project,
                variantKey = variantKey
            ) +
            project.kotlinParcelizeDeps() +
            BazelDependency.ProjectDependency(
                dependencyProject = project,
                suffix = matchedVariant.nameSuffix
            )

        val tags = if (kotlinExtension.enabledTransitiveReduction) {
            val transitiveMavenDeps = dependenciesDataSource.collectTransitiveMavenDeps(
                project = project,
                variantKey = variantKey
            )
            calculateDirectDependencyTags(name, deps + transitiveMavenDeps)
        } else emptyList()

        return AndroidUnitTestData(
            name = name,
            srcs = srcs,
            additionalSrcSets = additionalSrcSets,
            deps = deps.sorted(),
            tags = tags.sorted(),
            customPackage = packageName,
            associates = buildList { associate?.let(::add) },
            resources = resources,
            compose = project.hasCompose,
            testSize = testSize,
        )
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


    private fun unitTestSources(
        sourceSets: Sequence<AndroidSourceSet>
    ): Sequence<File> {
        val dirs = sourceSets.flatMap { it.java.srcDirs.asSequence() }
        val dirsKotlin = dirs.map { File(it.path.replace("/java", "/kotlin")) }
        return (dirs + dirsKotlin)
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
