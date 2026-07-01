/*
 * Copyright 2026 Grabtaxi Holdings PTE LTD (GRAB)
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
import com.grab.grazel.gradle.RepositoryAuth
import com.grab.grazel.gradle.RepositoryWithAuth
import com.grab.grazel.migrate.dependencies.LocalMavenProxyAuth
import com.grab.grazel.migrate.dependencies.LocalMavenProxyOrigin
import com.grab.grazel.migrate.dependencies.LocalMavenProxyServer
import com.grab.grazel.migrate.dependencies.LocalMavenProxyStats
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

internal abstract class LocalMavenProxyService :
    BuildService<LocalMavenProxyService.Params>,
    AutoCloseable {
    private val lock = Any()
    private var server: LocalMavenProxyServer? = null

    fun configure(facts: LocalMavenResolvedFacts) {
        synchronized(lock) {
            activeServer().configure(
                artifactIndex = facts.artifactIndex,
                pomFileResolver = facts.pomFileResolver
            )
        }
    }

    fun baseUrl(): String = synchronized(lock) {
        activeServer().baseUrl()
    }

    fun stats(): LocalMavenProxyStats = synchronized(lock) {
        server?.stats() ?: LocalMavenProxyStats()
    }

    private fun activeServer(): LocalMavenProxyServer {
        return server ?: LocalMavenProxyServer(
            cacheDir = parameters.cacheDir.get().asFile,
            origins = parameters.repositories.get().map { repository -> repository.toProxyOrigin() }
        ).also { newServer ->
            server = newServer
        }
    }

    override fun close() {
        synchronized(lock) {
            server?.close()
            server = null
        }
    }

    companion object {
        internal const val SERVICE_NAME = "LocalMavenProxyService"

        internal fun register(
            @RootProject rootProject: Project,
            repositories: ListProperty<RepositoryWithAuth>,
        ) = rootProject
            .gradle
            .sharedServices
            .registerIfAbsent(SERVICE_NAME, LocalMavenProxyService::class.java) {
                parameters.cacheDir.set(rootProject.layout.buildDirectory.dir("grazel/maven-proxy"))
                parameters.repositories.set(repositories)
            }
    }

    interface Params : BuildServiceParameters {
        val cacheDir: DirectoryProperty
        val repositories: ListProperty<RepositoryWithAuth>
    }
}

private fun RepositoryWithAuth.toProxyOrigin(): LocalMavenProxyOrigin =
    LocalMavenProxyOrigin(
        name = name,
        url = url,
        auth = when (val repositoryAuth = auth) {
            is RepositoryAuth.Basic -> LocalMavenProxyAuth.Basic(
                username = repositoryAuth.username,
                password = repositoryAuth.password
            )

            is RepositoryAuth.Header -> LocalMavenProxyAuth.Header(
                name = repositoryAuth.name,
                value = repositoryAuth.value
            )

            RepositoryAuth.None -> LocalMavenProxyAuth.None
        }
    )
