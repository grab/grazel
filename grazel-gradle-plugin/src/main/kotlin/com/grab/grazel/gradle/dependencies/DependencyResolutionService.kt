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

package com.grab.grazel.gradle.dependencies

import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.dependencies.DependencyResolutionService.Companion.SERVICE_NAME
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.internal.artifacts.result.DefaultResolvedComponentResult
import org.gradle.api.internal.artifacts.result.ResolvedComponentResultInternal
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.ConcurrentSkipListMap

/**
 * A [BuildService] to cache and maintain a project's resolved dependency resolution graph obtained after configuration
 * resolution. This cache lazily resolves dependencies the first time and caches result thereafter. Thread safe and
 * can be queried from multiple threads.
 */
internal interface DependencyResolutionService : BuildService<DependencyResolutionService.Params>, AutoCloseable {
    /**
     * Return the resolution result of dependencies (skips internal dependencies) from supported configurations.
     * Caches the result on first access and thread safe to access on multiple threads.
     *
     * @param project The project to resolve dependencies for. `project.path` is used as cache key.
     * @param configurations The list of [Configuration] that should be resolved.
     * @param skipCache Currently project path is used as cache key and repeat calls with different @param configurations
     *                  may result in cached result. `skipCache` may be used to force resolution again.
     */
    fun resolve(
        project: Project,
        configurations: Sequence<Configuration>,
        skipCache: Boolean = false
    ): List<ResolvedComponentResultInternal>

    companion object {
        internal const val SERVICE_NAME = "DependencyResolutionCache"
    }

    interface Params : BuildServiceParameters
}

internal abstract class DefaultDependencyResolutionService : DependencyResolutionService {
    /**
     * Thread safe lock-free map to hold resolution result of a project.
     *
     * Key: Project path
     */
    private val projectResolutionCache = ConcurrentSkipListMap<String, List<ResolvedComponentResultInternal>>()

    override fun resolve(
        project: Project,
        configurations: Sequence<Configuration>,
        skipCache: Boolean
    ): List<ResolvedComponentResultInternal> {
        fun resolveInternal(
            configurations: Sequence<Configuration>
        ) = configurations
            .map(Configuration::getIncoming)
            .flatMap { resolvableDependencies ->
                try {
                    resolvableDependencies
                        .resolutionResult
                        .allComponents
                        .asSequence()
                } catch (e: Exception) {
                    emptySequence<ResolvedComponentResult>()
                }
            }.filterIsInstance<DefaultResolvedComponentResult>()
            .filter { !it.toString().startsWith("project :") } // Exclude project dependencies
            .distinctBy(DefaultResolvedComponentResult::toString)
            .toList()
        return if (skipCache) {
            resolveInternal(configurations)
        } else {
            projectResolutionCache.getOrPut(project.path) { resolveInternal(configurations) }
        }
    }

    override fun close() {
        projectResolutionCache.clear()
    }

    companion object {
        internal fun register(@RootProject rootProject: Project) = rootProject
            .gradle
            .sharedServices
            .registerIfAbsent(SERVICE_NAME, DefaultDependencyResolutionService::class.java) {}
    }
}


