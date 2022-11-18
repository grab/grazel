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

package com.grab.grazel.gradle.dependencies

import com.android.build.gradle.internal.utils.toImmutableMap
import com.grab.grazel.GrazelExtension
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.ConfigurationDataSource
import com.grab.grazel.gradle.RepositoryDataSource
import com.grab.grazel.gradle.VariantInfo.Default
import com.grab.grazel.util.merge
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalDependency
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.internal.artifacts.repositories.DefaultMavenArtifactRepository
import org.gradle.api.internal.artifacts.result.DefaultResolvedComponentResult
import org.gradle.api.internal.artifacts.result.DefaultResolvedDependencyResult
import javax.inject.Inject

data class Repository(
    val name: String,
    val repository: DefaultMavenArtifactRepository
)

data class ExcludeRule(
    val group: String,
    val artifact: String
) {
    override fun toString(): String = "$group:$artifact"
}

class MavenExternalArtifact(
    val group: String,
    val name: String,
    val version: String,
    val repository: Repository,
    val componentResult: DefaultResolvedComponentResult,
    val excludeRules: List<ExcludeRule>
) {
    val id get() = "$group:$name:$version resolved from ${repository.name}"
    override fun toString() = id
}

internal class MavenInstallArtifactsCalculator
@Inject
constructor(
    @param:RootProject private val rootProject: Project,
    private val configurationDataSource: ConfigurationDataSource,
    private val repositoryDataSource: RepositoryDataSource,
    private val grazelExtension: GrazelExtension,
) {

    private val excludeArtifactsDenyList by lazy {
        grazelExtension.rules.mavenInstall.excludeArtifactsDenyList.get()
    }

    fun calculate(): Map<String, List<MavenExternalArtifact>> {
        val variantConfigs = calculateVariantConfigurations()
        // Resolve the dependencies in each variant bucket from the configuration
        val variantDependencies = resolveVariantDependencies(variantConfigs)
        // Remove all dependencies from flavors which are already present in default.
        return filterDependencies(variantDependencies)
    }

    /**
     * Takes a list of variants and the list of configurations in them to produce a `MavenExternalArtifact`
     * The data required for `MavenExternalArtifact` comes from different places and this method merges
     * from all of them to produce `Map` of variants and `MavenExternalArtifact`s.
     *
     *  * Repository is calculated from merging all repositories in the project.
     *  @see [RepositoryDataSource.allRepositoriesByName]
     *  * Exclude rules are calculated from `ExternalDependency` provided from `configuration.dependencies`
     *  * [ResolutionResult] is used to calculate dependency versions to ensure final version after
     *  dependency resolution is used
     *
     */
    private fun resolveVariantDependencies(
        variantConfigs: Map<String, List<Configuration>>
    ): Map<String, List<MavenExternalArtifact>> {
        val repositories = repositoryDataSource.allRepositoriesByName
        return variantConfigs.mapValues { (_, configurations) ->
            val excludeRules = calculateExcludeRules(configurations)
            configurations
                .asSequence()
                .filter { it.isCanBeResolved }
                .map { it.incoming }
                .flatMap { resolvableDependencies ->
                    try {
                        resolvableDependencies
                            .resolutionResult
                            .root
                            .dependencies
                            .asSequence()
                            .filterIsInstance<DefaultResolvedDependencyResult>()
                            .map { it.selected }
                            .filter { !it.toString().startsWith("project :") }
                            .filterIsInstance<DefaultResolvedComponentResult>()
                            .map { componentResult ->
                                val version = componentResult.moduleVersion!!
                                MavenExternalArtifact(
                                    group = version.group,
                                    version = version.version,
                                    name = version.name,
                                    repository = Repository(
                                        name = componentResult.repositoryName!!,
                                        repository = repositories[componentResult.repositoryName!!]!!
                                    ),
                                    componentResult = componentResult,
                                    excludeRules = excludeRules.getOrDefault(
                                        version.toString(),
                                        emptyList()
                                    )
                                )
                            }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        emptySequence()
                    }
                }.toSortedSet(compareBy(MavenExternalArtifact::id))
                .toList()
        }
    }

    /**
     * Calculate and merge exclude rules from all dependency declarations.
     *
     * @param configurations Configurations to merge exclude rules from
     * @return Map of maven id and its merged exclude rules.
     */
    private fun calculateExcludeRules(
        configurations: List<Configuration>
    ): Map<String, List<ExcludeRule>> {
        return configurations
            .asSequence()
            .flatMap { it.dependencies }
            .filter { it.group != null }
            .filterIsInstance<ExternalDependency>()
            .groupBy { dep -> "${dep.group}:${dep.name}:${dep.version}" }
            .mapValues { (_, artifacts) ->
                artifacts.flatMap { it.extractExcludeRules() }.distinct()
            }.filterValues { it.isNotEmpty() }
    }

    /**
     * Calculate a `Map` of `Variant` and its `Configuration`s for the whole project.
     */
    private fun calculateVariantConfigurations(): Map<String, List<Configuration>> {
        return rootProject
            .subprojects
            .map { project ->
                configurationDataSource
                    .configurationByVariant(project = project)
                    .mapKeys { it.key.toString() }
            }.merge { prev, next -> (prev + next) }
            .toImmutableMap()
    }

    /**
     * `variantDependencies` should contain all dependencies per flavor/variant but they might be
     * duplicated across all the buckets due to Gradle's configuration hierarchy. For example,
     * `flavor1DebugImplementation` will contain all dependencies from `default`. To find the
     * dependencies that only belong to `flavor1DebugImplementation` we filter all by looking against
     * dependencies in `default` configuration.
     */
    private fun filterDependencies(
        variantDependencies: Map<String, List<MavenExternalArtifact>>
    ): Map<String, List<MavenExternalArtifact>> {
        val defaultDependencies = variantDependencies.getOrDefault(Default.toString(), emptyList())
        val defaultDependenciesMap = defaultDependencies.groupBy { it.id }

        val filteredDependencies = mutableMapOf<String, List<MavenExternalArtifact>>().apply {
            put(Default.toString(), defaultDependencies)
        }
        variantDependencies
            .asSequence()
            .filter { it.key != Default.toString() }
            .forEach { (variantName, dependencies) ->
                filteredDependencies[variantName] = dependencies
                    .filter { !defaultDependenciesMap.contains(it.id) }
                    .sortedBy { it.id }
            }
        return filteredDependencies
            .filterValues { it.isNotEmpty() }
            .toImmutableMap()
    }

    private fun ExternalDependency.extractExcludeRules(): Set<ExcludeRule> {
        return excludeRules
            .asSequence()
            .map {
                @Suppress("USELESS_ELVIS") // Gradle lying, module can be null
                ExcludeRule(it.group, it.module ?: "")
            }
            .filterNot { it.artifact.isNullOrBlank() }
            .filterNot { excludeArtifactsDenyList.contains(it.toString()) }
            .toSet()
    }
}