/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate.android

import org.gradle.api.Project

internal data class JetifierConfig(
    val isEnabled: Boolean,
    val includeList: List<String>
)

internal class JetifierDataExtractor {
    /**
     * Infers if Jetifier is currently being used by looking for android.enableJetifier in gradle.properties.
     */
    private val Project.isJetifierEnabled
        get() = findProperty("android.enableJetifier")
            ?.toString()
            ?.toBoolean() ?: false

    /**
     * Extract jetifer data from the project for use with `rules_jvm_external`
     *
     * @param rootProject The root project of the current migration
     * @param includeList List of artifacts that should be jetified in maven coordinate format without version.
     * @param excludeList List of artifacts that should not jetified in maven coordinate format without version.
     * @param allArtifacts All external artifacts in the project. Can contain version information.
     *
     * @return JetifierConfig Extract jetifier configuration for the given input
     */
    fun extract(
        rootProject: Project,
        includeList: List<String>,
        excludeList: List<String>,
        allArtifacts: List<String>
    ): JetifierConfig {
        // TODO Add more validations for include and exclude list
        // Remove version information from all artifacts
        val allArtifactCoordinates = allArtifacts
            .asSequence()
            .map { fullMavenCoordinate ->
                val chunks = fullMavenCoordinate.split(":")
                chunks[0] + ":" + chunks[1]
            }
        return JetifierConfig(
            isEnabled = rootProject.isJetifierEnabled,
            includeList = ((allArtifactCoordinates + includeList.asSequence()) - excludeList.asSequence())
                // .filter { artifact -> !artifact.startsWith("androidx.") }  // Filter known artifacts
                .sorted()
                .toList()
        )
    }
}