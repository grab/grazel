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

package com.grab.grazel.migrate.kotlin

import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.extension.KotlinExtension
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.dependencies.BuildGraphType
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.dependencies.GradleDependencyToBazelDependency
import com.grab.grazel.migrate.android.FORMAT_UNIT_TEST_NAME
import com.grab.grazel.migrate.android.SourceSetType
import com.grab.grazel.migrate.android.filterNonDefaultSourceSetDirs
import com.grab.grazel.migrate.android.filterSourceSetPaths
import com.grab.grazel.migrate.common.TestSizeCalculator
import com.grab.grazel.migrate.common.calculateTestAssociates
import com.grab.grazel.migrate.dependencies.calculateDirectDependencyTags
import dagger.Lazy
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.internal.artifacts.dependencies.DefaultFileCollectionDependency
import org.gradle.kotlin.dsl.the
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal interface KotlinUnitTestDataExtractor {
    fun extract(project: Project): UnitTestData
}

@Singleton
internal class DefaultKotlinUnitTestDataExtractor
@Inject
constructor(
    private val dependenciesDataSource: DependenciesDataSource,
    private val dependencyGraphsProvider: Lazy<DependencyGraphs>,
    private val grazelExtension: GrazelExtension,
    private val gradleDependencyToBazelDependency: GradleDependencyToBazelDependency,
    private val testSizeCalculator: TestSizeCalculator,
) : KotlinUnitTestDataExtractor {

    private val kotlinExtension: KotlinExtension get() = grazelExtension.rules.kotlin

    private val projectDependencyGraphs get() = dependencyGraphsProvider.get()

    override fun extract(project: Project): UnitTestData {
        val name = FORMAT_UNIT_TEST_NAME.format(project.name, "")
        val sourceSets = project.the<KotlinJvmProjectExtension>().sourceSets

        val rawSrcs = kotlinTestSources(sourceSets)
        val srcs = rawSrcs
            .let { project.filterSourceSetPaths(it, SourceSetType.JAVA_KOTLIN.patterns) }
            .toList()
        val additionalSrcSets = rawSrcs
            .let(project::filterNonDefaultSourceSetDirs)
            .toList()

        val testSize = testSizeCalculator.calculate(
            name = name,
            sources = rawSrcs.toSet()
        )
        val projectDependency = BazelDependency.ProjectDependency(project)
        val associate = calculateTestAssociates(project)

        val deps: List<BazelDependency> = buildList {
            addAll(
                projectDependencyGraphs.directDependencies(
                    project,
                    BuildGraphType(ConfigurationScope.TEST)
                ).map { dependent ->
                    gradleDependencyToBazelDependency.map(project, dependent, null)
                }
            )
            addAll(
                dependenciesDataSource.collectMavenDeps(
                    project,
                    BuildGraphType(ConfigurationScope.TEST)
                )
            )
            addAll(project.kotlinParcelizeDeps())
            if (projectDependency.toString() != associate.toString()) {
                add(projectDependency)
            }
        }

        val tags = if (kotlinExtension.enabledTransitiveReduction) {
            val transitiveMavenDeps = dependenciesDataSource.collectTransitiveMavenDeps(
                project = project,
                buildGraphType = BuildGraphType(ConfigurationScope.TEST)
            )
            calculateDirectDependencyTags(name, deps + transitiveMavenDeps)
        } else emptyList()

        return UnitTestData(
            name = name,
            srcs = srcs,
            additionalSrcSets = additionalSrcSets,
            deps = deps.sorted(),
            associates = buildList { associate?.let(::add) },
            hasAndroidJarDep = project.hasAndroidJarDep(),
            testSize = testSize,
            tags = tags.sorted()
        )
    }

    private fun kotlinTestSources(
        sourceSets: NamedDomainObjectContainer<KotlinSourceSet>
    ): Sequence<File> = sourceSets
        .asSequence()
        .filter { it.name.lowercase().contains("test") }
        .flatMap { it.kotlin.srcDirs.asSequence() }
}

internal fun Project.hasAndroidJarDep(): Boolean {
    return configurations.findByName("compileOnly")
        ?.dependencies
        ?.filterIsInstance<DefaultFileCollectionDependency>()
        ?.any { dep -> dep.files.any { it.name.contains("android.jar") } } == true
}
