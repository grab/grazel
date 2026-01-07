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

import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.ConfigurationDataSource
import com.grab.grazel.gradle.dependencies.DependencyGraphsService.Companion.SERVICE_NAME
import com.grab.grazel.gradle.variant.AndroidVariantDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.gradle.api.Project
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * A [BuildService] to lazily build and cache dependency graphs during task execution phase.
 *
 * This ensures that Gradle configuration resolution happens during execution phase rather than
 * configuration phase, following Gradle best practices.
 */
internal interface DependencyGraphsService : BuildService<DependencyGraphsService.Params>,
    AutoCloseable {

    /**
     * Get the dependency graphs. Lazily builds the graphs on first access using dependencies
     * provided via [configure].
     *
     * @throws IllegalStateException if [configure] has not been called
     */
    fun get(): DependencyGraphs

    /**
     * Configure the service with dependencies needed to build graphs. This should be called during
     * task configuration phase. The actual graph building is deferred until [get] is called.
     *
     * @param rootProject The root Gradle project
     * @param dependenciesDataSource Source for project dependencies
     * @param configurationDataSource Source for configuration data
     * @param androidVariantDataSource Source for Android variant data
     */
    fun configure(
        rootProject: Project,
        dependenciesDataSource: DependenciesDataSource,
        configurationDataSource: ConfigurationDataSource,
        androidVariantDataSource: AndroidVariantDataSource
    )

    companion object {
        internal const val SERVICE_NAME = "DependencyGraphsService"
    }

    interface Params : BuildServiceParameters
}

internal abstract class DefaultDependencyGraphsService : DependencyGraphsService {
    private var dependencyGraphs: DependencyGraphs? = null
    private val buildMutex = Mutex()

    private var rootProject: Project? = null
    private var dependenciesDataSource: DependenciesDataSource? = null
    private var configurationDataSource: ConfigurationDataSource? = null
    private var androidVariantDataSource: AndroidVariantDataSource? = null

    override fun configure(
        rootProject: Project,
        dependenciesDataSource: DependenciesDataSource,
        configurationDataSource: ConfigurationDataSource,
        androidVariantDataSource: AndroidVariantDataSource
    ) {
        this.rootProject = rootProject
        this.dependenciesDataSource = dependenciesDataSource
        this.configurationDataSource = configurationDataSource
        this.androidVariantDataSource = androidVariantDataSource
    }

    override fun get(): DependencyGraphs {
        // Lazily build on first access
        if (dependencyGraphs == null) {
            val root = rootProject
                ?: error("DependencyGraphsService not configured. Call configure() first during task configuration.")
            val deps = dependenciesDataSource
                ?: error("DependencyGraphsService not configured. Call configure() first during task configuration.")
            val config = configurationDataSource
                ?: error("DependencyGraphsService not configured. Call configure() first during task configuration.")
            val variants = androidVariantDataSource
                ?: error("DependencyGraphsService not configured. Call configure() first during task configuration.")

            runBlocking {
                buildMutex.withLock {
                    if (dependencyGraphs == null) {
                        dependencyGraphs = DependenciesGraphsBuilder(
                            root,
                            deps,
                            config,
                            variants
                        ).build()
                    }
                }
            }
        }
        return dependencyGraphs!!
    }

    override fun close() {
        dependencyGraphs = null
        rootProject = null
        dependenciesDataSource = null
        configurationDataSource = null
        androidVariantDataSource = null
    }

    companion object {
        internal fun register(@RootProject rootProject: Project) = rootProject
            .gradle
            .sharedServices
            .registerIfAbsent(SERVICE_NAME, DefaultDependencyGraphsService::class.java) {}
    }
}
