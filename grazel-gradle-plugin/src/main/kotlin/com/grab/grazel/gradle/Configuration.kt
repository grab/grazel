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
import com.grab.grazel.GrazelExtension
import com.grab.grazel.gradle.dependencies.BuildGraphType
import com.grab.grazel.gradle.variant.AndroidVariantDataSource
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin.Companion.findKaptConfiguration
import javax.inject.Inject
import javax.inject.Singleton

enum class ConfigurationScope(val scopeName: String) {
    BUILD(""),
    TEST("UnitTest"),
    ANDROID_TEST("AndroidTest");
}

internal fun GrazelExtension.configurationScopes(): Array<ConfigurationScope> {
    return if (rules.test.enableTestMigration) {
        arrayOf(
            ConfigurationScope.BUILD,
            ConfigurationScope.TEST,
            ConfigurationScope.ANDROID_TEST,
        )
    } else {
        arrayOf(ConfigurationScope.BUILD)
    }
}

internal interface ConfigurationDataSource {
    /**
     * Return a sequence of the configurations which are filtered out by the ignore flavors & build variants
     * these configuration can be queried or resolved.
     */
    fun resolvedConfigurations(
        project: Project,
        vararg buildGraphTypes: BuildGraphType
    ): Sequence<Configuration>

    /**
     * Return a sequence of the configurations filtered out by the ignore flavors, build variants and the configuration scopes
     * If the scopes is empty, the build scope will be used by default.
     */
    fun configurations(
        project: Project,
        vararg scopes: ConfigurationScope
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
        vararg scopes: ConfigurationScope
    ): Sequence<Configuration> {
        val ignoreFlavors = androidVariantDataSource.getIgnoredFlavors(project)
        val ignoreVariants = androidVariantDataSource.getIgnoredVariants(project)
        return filterConfigurations(project, scopes)
            .filter { config ->
                !config.name.let { configurationName ->
                    ignoreFlavors.any { configurationName.contains(it.name, true) }
                        || ignoreVariants.any { configurationName.contains(it.name, true) }
                }
            }
    }

    /**
     * Only allow relevant configurations required for build. Ideally for android projects we could
     * rely on [BaseVariant.getCompileConfiguration] etc but other configurations added by plugins won't
     * be present there. Hence we get all configurations in the project and filter by name with some
     * assumptions
     */
    private fun filterConfigurations(
        project: Project,
        scopes: Array<out ConfigurationScope> = ConfigurationScope.values(),
        variantNameFilter: String? = null
    ): Sequence<Configuration> {
        return project.configurations
            .asSequence()
            .filter { !it.name.contains("classpath", true) && !it.name.contains("lint") }
            .filter { !it.name.contains("coreLibraryDesugaring") }
            .filter { !it.name.startsWith("_") }
            .filter { !it.name.contains("archives") }
            .filter { !it.name.contains("KaptWorker") }
            .filter { !it.name.contains("androidJacocoAnt") }
            .filter { !it.isDynamicConfiguration() } // Remove when Grazel support dynamic-feature plugin
            .filter { configuration ->
                when {
                    scopes.isEmpty() -> configuration.isNotTest() // If the scopes is empty, the build scope will be used by default.
                    else -> scopes.any { scope ->
                        when (scope) {
                            ConfigurationScope.TEST -> !configuration.isAndroidTest() && configuration.isUnitTest()
                            ConfigurationScope.ANDROID_TEST -> !configuration.isUnitTest()
                            ConfigurationScope.BUILD -> configuration.isNotTest()
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
        vararg buildGraphTypes: BuildGraphType
    ): Sequence<Configuration> {
        return configurations(
            project,
            *buildGraphTypes.map { it.configurationScope }.toTypedArray()
        ).filter { it.isCanBeResolved }
    }
}

internal fun Configuration.isUnitTest() = name.contains("UnitTest", true) || name.startsWith("test")
internal fun Configuration.isAndroidTest() = name.contains("androidTest", true)
internal fun Configuration.isDynamicConfiguration() = name.contains("ReverseMetadata", true)
internal fun Configuration.isNotTest() = !name.contains("test", true)