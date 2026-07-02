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
import com.grab.grazel.bazel.rules.KOTLIN_PARCELIZE_TARGET
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.extension.KotlinExtension
import com.grab.grazel.gradle.dependencies.DefaultDependencyGraphsService
import com.grab.grazel.gradle.dependencies.DependenciesDataSource
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.variant.VariantGraphKey
import com.grab.grazel.gradle.dependencies.GradleDependencyToBazelDependency
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.hasKotlinAndroidExtensions
import com.grab.grazel.migrate.android.SourceSetType
import com.grab.grazel.migrate.android.SourceSetType.ASSETS
import com.grab.grazel.migrate.android.SourceSetType.JAVA
import com.grab.grazel.migrate.android.SourceSetType.JAVA_KOTLIN
import com.grab.grazel.migrate.android.SourceSetType.KOTLIN
import com.grab.grazel.migrate.android.SourceSetType.RESOURCES
import com.grab.grazel.migrate.android.filterSourceSetPaths
import com.grab.grazel.migrate.android.lintConfigs
import com.grab.grazel.migrate.dependencies.calculateCompileFilterTags
import com.grab.grazel.util.GradleProvider
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.kotlin.dsl.the
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal interface KotlinProjectDataExtractor {
    fun extract(project: Project): KotlinProjectData
}

@Singleton
internal class DefaultKotlinProjectDataExtractor
@Inject constructor(
    private val dependenciesDataSource: DependenciesDataSource,
    private val dependencyGraphsService: GradleProvider<DefaultDependencyGraphsService>,
    private val grazelExtension: GrazelExtension,
    private val gradleDependencyToBazelDependency: GradleDependencyToBazelDependency,
) : KotlinProjectDataExtractor {

    private val kotlinExtension: KotlinExtension get() = grazelExtension.rules.kotlin

    private val projectDependencyGraphs: DependencyGraphs get() = dependencyGraphsService.get().get()

    override fun extract(project: Project): KotlinProjectData {
        val name = project.name
        val sourceSets = project.the<KotlinJvmProjectExtension>().sourceSets
        val srcs = kotlinSources(project, sourceSets, JAVA_KOTLIN).toList()
        val resources = kotlinSources(project, sourceSets, RESOURCES).toList()

        val variantKey = VariantGraphKey.from(project, "default", VariantType.JvmBuild)
        val deps = projectDependencyGraphs.directDependenciesByVariant(
            project = project,
            variantKey = variantKey
        ).map { dependent ->
            gradleDependencyToBazelDependency.map(
                project = project,
                dependency = dependent,
                matchedVariant = null
            )
        } + dependenciesDataSource.collectMavenDeps(
            project = project,
            variantKey = variantKey
        ) + androidJarDeps(project) + kotlinParcelizeDeps(project)

        val tags = if (kotlinExtension.enabledTransitiveReduction) {
            calculateCompileFilterTags(
                self = name,
                directDependencies = deps,
                transitiveMavenDependencies = dependenciesDataSource.collectTransitiveMavenDeps(
                    project = project,
                    variantKey = variantKey
                )
            )
        } else emptyList()

        val plugins = dependenciesDataSource.collectKspPluginDeps(project, variantKey)

        return KotlinProjectData(
            name = name,
            srcs = srcs,
            res = resources,
            deps = deps,
            tags = tags,
            lintConfigData = lintConfigs(project),
            plugins = plugins
        )
    }

    private fun kotlinSources(
        project: Project,
        sourceSets: NamedDomainObjectContainer<KotlinSourceSet>,
        sourceSetType: SourceSetType
    ): Sequence<String> {
        val sourceSetChoosers: KotlinSourceSet.() -> Sequence<File> = when (sourceSetType) {
            JAVA, JAVA_KOTLIN, KOTLIN -> {
                { kotlin.srcDirs.asSequence() }
            }

            RESOURCES -> {
                { resources.srcDirs.asSequence() }
            }

            ASSETS -> {
                { emptySequence() }
            }
        }
        val dirs = sourceSets.asSequence()
            .filter { sourceSet -> !sourceSet.name.lowercase().contains("test") }
            .flatMap(sourceSetChoosers)
        return project.filterSourceSetPaths(dirs, sourceSetType.patterns)
    }
}

internal fun kotlinParcelizeDeps(project: Project): List<BazelDependency.StringDependency> {
    return when {
        project.hasKotlinAndroidExtensions -> listOf(KOTLIN_PARCELIZE_TARGET)
        else -> emptyList()
    }
}

internal fun androidJarDeps(project: Project): List<BazelDependency> = if (hasAndroidJarDep(project)) {
    listOf(BazelDependency.StringDependency("//shared_versions:android_sdk"))
} else {
    emptyList()
}
