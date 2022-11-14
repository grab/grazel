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
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.ConfigurationDataSource
import com.grab.grazel.gradle.VariantInfo.Default
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.internal.artifacts.result.DefaultResolvedComponentResult
import org.gradle.api.internal.artifacts.result.DefaultResolvedDependencyResult
import javax.inject.Inject

internal class MavenInstallArtifactsCalculator
@Inject
constructor(
    @param:RootProject private val rootProject: Project,
    private val configurationDataSource: ConfigurationDataSource,
) {

    fun calculate(): Map<String, List<DefaultResolvedComponentResult>> {
        val variantConfigs = calculateVariantConfigurations()
        // Resolve the dependencies in each variant bucket from the configuration
        val variantDependencies = resolveVariantDependencies(variantConfigs)
        // Remove all dependencies from flavors which are already present in default.
        return filterDependencies(variantDependencies)
    }

    private fun resolveVariantDependencies(
        variantConfigs: Map<String, List<Configuration>>
    ): Map<String, List<DefaultResolvedComponentResult>> {
        return variantConfigs.mapValues { (_, configurations) ->
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
                    } catch (e: Exception) {
                        emptySequence<ResolvedComponentResult>()
                    }
                }.filterIsInstance<DefaultResolvedComponentResult>()
                .sortedBy(DefaultResolvedComponentResult::toString)
                .distinctBy(DefaultResolvedComponentResult::toString)
                .toList()
        }
    }

    /**
     * Calculate a `Map` of `Variant` and its `Configuration`s for the whole project.
     */
    private fun calculateVariantConfigurations(): Map<String, List<Configuration>> {
        val variantConfigs = mutableMapOf<String, List<Configuration>>()
        rootProject.subprojects.forEach { project ->
            val variantConfigMap = configurationDataSource.configurationByVariant(project)
            variantConfigMap.forEach { (variantInfo, configurations) ->
                val prevConfigurations = variantConfigs.getOrDefault(
                    key = variantInfo.toString(),
                    defaultValue = emptyList()
                )
                variantConfigs[variantInfo.toString()] = configurations + prevConfigurations
            }
        }
        return variantConfigs.toImmutableMap()
    }

    /**
     * `variantDependencies` should contain all dependencies per flavor/variant but they might be
     * duplicated across all the buckets due to Gradle's configuration hierarchy. For example,
     * `flavor1DebugImplementation` will contain all dependencies from `default`. To find the
     * dependencies that only belong to `flavor1DebugImplementation` we filter all by looking against
     * dependencies in `default` configuration.
     */
    private fun filterDependencies(
        variantDependencies: Map<String, List<DefaultResolvedComponentResult>>
    ): Map<String, List<DefaultResolvedComponentResult>> {
        val defaultDependencies = variantDependencies.getOrDefault(Default.toString(), emptyList())
        val filteredDependencies = mutableMapOf<String, List<DefaultResolvedComponentResult>>()
            .apply {
                put(Default.toString(), defaultDependencies)
            }
        variantDependencies.forEach { (variantName, dependencies) ->
            if (variantName != Default.toString()) {
                filteredDependencies[variantName] = dependencies.filter { componentResult ->
                    defaultDependencies.none { componentResult.toString() == it.toString() }
                }
            }
        }
        return filteredDependencies
            .filterValues { it.isNotEmpty() }
            .toImmutableMap()
    }
}