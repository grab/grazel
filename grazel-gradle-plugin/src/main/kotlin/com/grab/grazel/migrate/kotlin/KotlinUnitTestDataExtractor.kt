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

package com.grab.grazel.migrate.kotlin

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.dependencies.directProjectDependencies
import com.grab.grazel.migrate.android.FORMAT_UNIT_TEST_NAME
import com.grab.grazel.migrate.android.SourceSetType
import com.grab.grazel.migrate.android.collectMavenDeps
import com.grab.grazel.migrate.android.filterSourceSetPaths
import com.grab.grazel.migrate.common.calculateTestAssociate
import dagger.Lazy
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.internal.artifacts.dependencies.DefaultSelfResolvingDependency
import org.gradle.kotlin.dsl.the
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import javax.inject.Inject
import javax.inject.Singleton

internal interface KotlinUnitTestDataExtractor {
    fun extract(project: Project): UnitTestData
}

@Singleton
internal class DefaultKotlinUnitTestDataExtractor @Inject constructor(
    private val dependenciesDataSource: DependenciesDataSource,
    private val dependencyGraphsProvider: Lazy<DependencyGraphs>
) : KotlinUnitTestDataExtractor {

    private val projectDependencyGraphs get() = dependencyGraphsProvider.get()

    override fun extract(project: Project): UnitTestData {
        val sourceSets = project.the<KotlinJvmProjectExtension>().sourceSets

        val srcs = project.kotlinTestSources(sourceSets).toList()

        val projectDependency = BazelDependency.ProjectDependency(project)
        val associate = calculateTestAssociate(project)

        val deps: List<BazelDependency> = buildList {
            addAll(
                projectDependencyGraphs.directProjectDependencies(
                    project,
                    ConfigurationScope.TEST
                )
            )
            addAll(dependenciesDataSource.collectMavenDeps(project, ConfigurationScope.TEST))
            addAll(project.kotlinParcelizeDeps())
            if (projectDependency.toString() != associate.toString()) {
                add(projectDependency)
            }
        }
        return UnitTestData(
            name = FORMAT_UNIT_TEST_NAME.format(project.name),
            srcs = srcs,
            deps = deps,
            associates = buildList { associate?.let(::add) },
            hasAndroidJarDep = project.hasAndroidJarDep(),
        )
    }

    private fun Project.kotlinTestSources(
        sourceSets: NamedDomainObjectContainer<KotlinSourceSet>
    ): Sequence<String> {
        val dirs = sourceSets
            .asSequence()
            .filter { it.name.toLowerCase().contains("test") }
            .flatMap { it.kotlin.srcDirs.asSequence() }
        return filterSourceSetPaths(dirs, SourceSetType.JAVA_KOTLIN.patterns)
    }
}

internal fun Project.hasAndroidJarDep(): Boolean {
    return configurations.findByName("compileOnly")
        ?.dependencies
        ?.filterIsInstance<DefaultSelfResolvingDependency>()
        ?.any { dep -> dep.files.any { it.name.contains("android.jar") } } == true
}
