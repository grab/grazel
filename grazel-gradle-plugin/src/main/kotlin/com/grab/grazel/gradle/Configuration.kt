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

package com.grab.grazel.gradle

import com.android.build.gradle.api.BaseVariant
import com.grab.grazel.gradle.variant.AndroidVariantDataSource
import com.grab.grazel.gradle.variant.VariantType
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin.Companion.findKaptConfiguration
import javax.inject.Inject
import javax.inject.Singleton

internal interface ConfigurationDataSource {
    /**
     * Return a sequence of the configurations which are filtered out by the ignore flavors & build variants
     * these configuration can be queried or resolved.
     */
    fun resolvedConfigurations(
        project: Project,
        vararg variantTypes: VariantType
    ): Sequence<Configuration>

    /**
     * Return a sequence of the configurations filtered out by the ignore flavors, build variants and the variant types.
     * If the variantTypes is empty, AndroidBuild scope will be used by default.
     */
    fun configurations(
        project: Project,
        vararg variantTypes: VariantType
    ): Sequence<Configuration>

    fun isThisConfigurationBelongsToThisVariants(
        project: Project,
        vararg variants: BaseVariant?,
        configuration: Configuration
    ): Boolean
}

@Singleton
internal class DefaultConfigurationDataSource @Inject constructor(
    private val androidVariantDataSource: AndroidVariantDataSource
) : ConfigurationDataSource {

    override fun configurations(
        project: Project,
        vararg variantTypes: VariantType
    ): Sequence<Configuration> {
        val ignoreFlavors = androidVariantDataSource.getIgnoredFlavors(project)
        val ignoreVariants = androidVariantDataSource.getIgnoredVariants(project)
        return filterConfigurationsByVariantType(project, variantTypes)
            .filter { config ->
                !config.name.let { configurationName ->
                    ignoreFlavors.any { configurationName.contains(it.name, true) }
                        || ignoreVariants.any { configurationName.contains(it.name, true) }
                }
            }
    }

    /**
     * Filter configurations by VariantType.
     */
    private fun filterConfigurationsByVariantType(
        project: Project,
        variantTypes: Array<out VariantType> = arrayOf(
            VariantType.AndroidBuild,
            VariantType.Test,
            VariantType.AndroidTest
        ),
        variantNameFilter: String? = null
    ): Sequence<Configuration> {
        return project.configurations
            .asSequence()
            .filter { !it.name.contains("classpath", true) && !it.name.contains("lint") }
            .filter { !it.name.contains("coreLibraryDesugaring") }
            .filter { !it.name.startsWith("_") }
            .filter { !it.name.contains("archives") }
            .filter { !it.name.contains("KaptWorker") }
            .filter { !it.name.contains("Jacoco", true) }
            .filter { !it.name.contains("androidSdkImage") }
            .filter { !it.isDynamicConfiguration() } // Remove when Grazel support dynamic-feature plugin
            .filter { configuration ->
                when {
                    variantTypes.isEmpty() -> configuration.isNotTest() // Default to build scope
                    else -> variantTypes.any { variantType ->
                        when (variantType) {
                            VariantType.Test -> !configuration.isAndroidTest() && configuration.isUnitTest()
                            VariantType.AndroidTest -> !configuration.isUnitTest()
                            else -> configuration.isNotTest() // AndroidBuild, JvmBuild, Lint
                        }
                    }
                }
            }
            .filter {
                if (variantNameFilter != null) it.name.startsWith(variantNameFilter) else true
            }.distinct()
    }

    override fun isThisConfigurationBelongsToThisVariants(
        project: Project,
        vararg variants: BaseVariant?,
        configuration: Configuration
    ) = variants.any { variant ->
        variant == null ||
            variant.compileConfiguration.hierarchy.contains(configuration) ||
            variant.runtimeConfiguration.hierarchy.contains(configuration) ||
            variant.annotationProcessorConfiguration.hierarchy.contains(configuration) ||
            variant.sourceSets.map { it.name }.any { sourceSetName ->
                project.findKaptConfiguration(sourceSetName)?.name == configuration.name
            } ||
            configuration.name == "kotlin-extension"
    }

    override fun resolvedConfigurations(
        project: Project,
        vararg variantTypes: VariantType
    ): Sequence<Configuration> {
        return configurations(project, *variantTypes)
            .filter { it.isCanBeResolved }
    }
}

internal fun Configuration.isUnitTest() = name.contains("UnitTest", true) || name.startsWith("test")
internal fun Configuration.isAndroidTest() = name.contains("androidTest", true)
internal fun Configuration.isDynamicConfiguration() = name.contains("ReverseMetadata", true)
internal fun Configuration.isNotTest() = !name.contains("test", true)