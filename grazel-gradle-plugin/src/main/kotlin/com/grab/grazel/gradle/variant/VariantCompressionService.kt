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

package com.grab.grazel.gradle.variant

import com.grab.grazel.di.qualifiers.RootProject
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * A [BuildService] that stores and retrieves [VariantCompressionResult] for Android projects.
 *
 * This service acts as a cache for variant compression results computed during the migration
 * process. Projects register their compression results, which can then be queried by other parts of
 * the build system that need variant mapping information.
 *
 * The service stores results in memory during the build and is cleared when the build completes.
 */
internal interface VariantCompressionService : BuildService<VariantCompressionService.Params>,
    AutoCloseable {

    /**
     * Register a compression result for a project.
     *
     * If a result was already registered for this project, it will be replaced.
     *
     * @param projectPath The Gradle project path (e.g., ":app", ":lib:feature")
     * @param result The compression result to store
     */
    fun register(projectPath: String, result: VariantCompressionResult)

    /**
     * Get the compression result for a project.
     *
     * @param projectPath The Gradle project path
     * @return The compression result if registered, null otherwise
     */
    fun get(projectPath: String): VariantCompressionResult?

    /**
     * Check if a compression result has been registered for a project.
     *
     * @param projectPath The Gradle project path
     * @return true if a result is registered, false otherwise
     */
    fun isRegistered(projectPath: String): Boolean

    companion object {
        internal const val SERVICE_NAME = "VariantCompressionService"
    }

    interface Params : BuildServiceParameters
}

/**
 * Default implementation of [VariantCompressionService].
 *
 * Stores compression results in a mutable map keyed by project path.
 */
internal abstract class DefaultVariantCompressionService : VariantCompressionService {
    private val results = mutableMapOf<String, VariantCompressionResult>()

    override fun register(projectPath: String, result: VariantCompressionResult) {
        results[projectPath] = result
    }

    override fun get(projectPath: String): VariantCompressionResult? {
        return results[projectPath]
    }

    override fun isRegistered(projectPath: String): Boolean {
        return projectPath in results
    }

    override fun close() {
        results.clear()
    }

    companion object {
        /**
         * Register the service with the root project's shared services.
         *
         * @param rootProject The root Gradle project
         * @return A provider for the registered service
         */
        internal fun register(@RootProject rootProject: Project) = rootProject
            .gradle
            .sharedServices
            .registerIfAbsent(
                VariantCompressionService.SERVICE_NAME,
                DefaultVariantCompressionService::class.java
            ) {}
    }
}

/**
 * Resolves the target suffix for a variant with fallback and optional logging.
 *
 * @param projectPath The project path to look up
 * @param variantName The variant name to resolve
 * @param fallbackSuffix The suffix to use if lookup fails
 * @param logger Optional logger for warnings when fallback is used
 * @return The resolved suffix (compressed if available, fallback otherwise)
 */
internal fun VariantCompressionService.resolveSuffix(
    projectPath: String,
    variantName: String,
    fallbackSuffix: String,
    logger: Logger? = null
): String {
    val result = get(projectPath) ?: run {
        logger?.warn("No compression result for $projectPath, using fallback suffix")
        return fallbackSuffix
    }
    return result.suffixForVariantOrNull(variantName) ?: run {
        logger?.warn("Variant $variantName not found in compression result for $projectPath, using fallback suffix")
        fallbackSuffix
    }
}
