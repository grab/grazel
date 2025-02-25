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

import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.dependencies.DependencyResolutionService.Companion.SERVICE_NAME
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.tasks.internal.ComputeWorkspaceDependenciesTask
import com.grab.grazel.tasks.internal.GenerateBazelScriptsTask
import com.grab.grazel.util.fromJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.gradle.api.Project
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.io.File

/**
 * A [BuildService] to cache and store dependencies computed for `WORKSPACE` by
 * [ComputeWorkspaceDependenciesTask] and later used by [GenerateBazelScriptsTask]
 */
internal interface DependencyResolutionService : BuildService<DependencyResolutionService.Params>,
    AutoCloseable {
    /**
     * For a given variant hierarchy and `group` and `name`, the function will try to look for the
     * dependency in each of the variant hierarchy and return the first one found.
     *
     * For example, if `androidx.activity:activity` is given and it was categorized under
     * `@maven` repository then will return `@maven//:androidx_activity_activity` in form of
     * [MavenDependency]
     *
     * @param variants Variant hierarchy sorted by priority
     * @param group Maven group name
     * @param name Maven artifact name
     */
    fun getMavenDependency(
        variants: Set<String>,
        group: String,
        name: String
    ): MavenDependency?

    /**
     * Get transitive dependencies for a given dependency identified by its shortId
     *
     * @param shortId The short identifier for the dependency
     * @return Set of transitive dependencies
     */
    fun getTransitiveDependencies(shortId: String): Set<String>

    fun init(workspaceDependenciesJson: File): WorkspaceDependencies

    companion object {
        internal const val SERVICE_NAME = "DependencyResolutionCache"
    }

    interface Params : BuildServiceParameters
}

internal abstract class DefaultDependencyResolutionService : DependencyResolutionService {
    private var mavenInstallStore: MavenInstallStore? = null
    private var transitiveDependenciesStore: TransitiveDependenciesStore? = null
    private var workspaceDependencies: WorkspaceDependencies? = null
    private val initMutex = Mutex()
    private val mavenStoreLock = Mutex()
    private val transitiveDepsStoreLock = Mutex()

    override fun getMavenDependency(
        variants: Set<String>,
        group: String,
        name: String
    ): MavenDependency? = mavenInstallStore?.get(variants, group, name)

    override fun getTransitiveDependencies(shortId: String): Set<String> =
        transitiveDependenciesStore?.get(shortId) ?: emptySet()

    override fun init(workspaceDependenciesJson: File): WorkspaceDependencies {
        if (workspaceDependencies == null) {
            runBlocking {
                initMutex.withLock {
                    if (workspaceDependencies == null) {
                        workspaceDependencies = fromJson<WorkspaceDependencies>(
                            workspaceDependenciesJson
                        )
                        populateCache(workspaceDependencies!!)
                    }
                }
            }
        }
        return workspaceDependencies!!
    }

    internal fun populateCache(workspaceDependencies: WorkspaceDependencies) {
        populateMavenStore(workspaceDependencies)
        populateTransitiveDependenciesStore(workspaceDependencies)
    }

    internal fun populateMavenStore(workspaceDependencies: WorkspaceDependencies) {
        if (mavenInstallStore == null) {
            runBlocking {
                mavenStoreLock.withLock {
                    if (mavenInstallStore == null) {
                        mavenInstallStore = DefaultMavenInstallStore().apply {
                            workspaceDependencies.result.forEach { (variantName, dependencies) ->
                                dependencies.forEach { dependency ->
                                    val (group, name, _) = dependency.id.split(":")
                                    set(variantName, group, name)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    internal fun populateTransitiveDependenciesStore(workspaceDependencies: WorkspaceDependencies) {
        if (transitiveDependenciesStore == null) {
            runBlocking {
                transitiveDepsStoreLock.withLock {
                    if (transitiveDependenciesStore == null) {
                        transitiveDependenciesStore = DefaultTransitiveDependenciesStore().apply {
                            workspaceDependencies.transitiveClasspath.forEach { (shortId, dependencies) ->
                                set(shortId, dependencies.toSet())
                            }
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        mavenInstallStore?.close()
        mavenInstallStore = null
        transitiveDependenciesStore?.close()
        transitiveDependenciesStore = null
    }

    companion object {
        internal fun register(@RootProject rootProject: Project) = rootProject
            .gradle
            .sharedServices
            .registerIfAbsent(SERVICE_NAME, DefaultDependencyResolutionService::class.java) {}
    }
}