/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import javax.inject.Inject
import javax.inject.Singleton

internal interface ConfigurationDataSource {
    /**
     * Return a sequence of the configurations which are filtered out by the ignore flavors & build variants
     * these configuration can be queried or resolved.
     */
    fun resolvedConfigurations(project: Project): Sequence<Configuration>

    /**
     * Return a sequence of the configurations which are filtered out by the ignore flavors & build variants
     */
    fun configurations(project: Project): Sequence<Configuration>
}

@Singleton
internal class DefaultConfigurationDataSource @Inject constructor(
    private val androidBuildVariantDataSource: AndroidBuildVariantDataSource
) : ConfigurationDataSource {

    override fun configurations(project: Project): Sequence<Configuration> {
        val ignoreFlavors = androidBuildVariantDataSource.getIgnoredFlavors(project)
        val ignoreVariants = androidBuildVariantDataSource.getIgnoredVariants(project)
        return project.configurations
            .asSequence()
            .filter { !it.name.contains("classpath", true) && !it.name.contains("lint") }
            .filter { !it.name.contains("test", true) } // TODO Remove when tests are supported
            .filter { !it.name.contains("coreLibraryDesugaring") }
            .filter { !it.name.contains("_internal_aapt2_binary") }
            .filter { !it.name.contains("archives") }
            .filter { config ->
                !config.name.let { configurationName ->
                    ignoreFlavors.any { configurationName.contains(it.name, true) }
                            || ignoreVariants.any { configurationName.contains(it.name, true) }
                }
            }
    }

    override fun resolvedConfigurations(project: Project): Sequence<Configuration> {
        return configurations(project).filter { it.isCanBeResolved }
    }
}