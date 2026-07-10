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

package com.grab.grazel.proxy

import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.RepositoryAuth
import com.grab.grazel.gradle.RepositoryWithAuth
import com.grab.grazel.maven.LocalMavenResolutionStats
import com.grab.grazel.maven.mavenRepositoryUrlWithBasicCredentials
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
    private var serverOrigins: List<LocalMavenProxyOrigin>? = null

    fun configure(
        facts: LocalMavenResolvedFacts,
        allowedOriginArtifactPaths: Set<String> = emptySet(),
        canonicalRepositoryUrls: Set<String> = emptySet(),
    ): LocalMavenProxyRepositoryMappings {
        return synchronized(lock) {
            val proxyPlans = localMavenRepositoryProxyPlans(
                repositories = parameters.repositories.get(),
                canonicalRepositoryUrls = canonicalRepositoryUrls
            )
            val activeServer = activeServer(proxyPlans)
            activeServer.configure(
                artifactIndex = facts.artifactIndex,
                knownComponentGavs = facts.knownComponentGavs,
                metadataOnlyGavs = facts.metadataOnlyGavs,
                allowedOriginArtifactPaths = allowedOriginArtifactPaths,
                pomFileResolver = facts.pomFileResolver
            )
            localMavenRepositoryProxyMappingsFrom(
                baseUrl = activeServer.baseUrl().trimEnd('/'),
                proxyPlans = proxyPlans
            )
        }
    }

    fun stats(): LocalMavenResolutionStats = synchronized(lock) {
        server?.stats() ?: LocalMavenResolutionStats()
    }

    private fun activeServer(proxyPlans: List<LocalMavenRepositoryProxyPlan>): LocalMavenProxyServer {
        val origins = proxyPlans.map { plan -> plan.origin }
        server?.takeIf { serverOrigins == origins }?.let { activeServer ->
            return activeServer
        }
        server?.close()
        server = null
        serverOrigins = null
        return LocalMavenProxyServer(
            cacheDir = parameters.cacheDir.get().asFile,
            origins = origins
        ).also { newServer ->
            server = newServer
            serverOrigins = origins
        }
    }

    override fun close() {
        synchronized(lock) {
            server?.close()
            server = null
            serverOrigins = null
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

private fun toProxyOrigin(repository: RepositoryWithAuth): LocalMavenProxyOrigin =
    LocalMavenProxyOrigin(
        name = repository.name,
        url = repository.url,
        auth = repository.auth
    )

internal fun localMavenProxyOrigins(
    repositories: List<RepositoryWithAuth>,
    canonicalRepositoryUrls: Set<String>,
): List<LocalMavenProxyOrigin> =
    localMavenRepositoryProxyPlans(
        repositories = repositories,
        canonicalRepositoryUrls = canonicalRepositoryUrls
    ).map { plan -> plan.origin }

internal data class LocalMavenProxyRepositoryMappings(
    val proxyToCanonicalUrl: Map<String, String>,
    val canonicalToProxyUrl: Map<String, String>,
)

internal fun localMavenRepositoryProxyMappings(
    baseUrl: String,
    repositories: List<RepositoryWithAuth>,
    canonicalRepositoryUrls: Set<String>,
): LocalMavenProxyRepositoryMappings =
    localMavenRepositoryProxyMappingsFrom(
        baseUrl = baseUrl,
        proxyPlans = localMavenRepositoryProxyPlans(
            repositories = repositories,
            canonicalRepositoryUrls = canonicalRepositoryUrls
        )
    )

private fun localMavenRepositoryProxyMappingsFrom(
    baseUrl: String,
    proxyPlans: List<LocalMavenRepositoryProxyPlan>,
): LocalMavenProxyRepositoryMappings {
    val proxyToCanonicalUrl = linkedMapOf<String, String>()
    val canonicalToProxyUrl = linkedMapOf<String, String>()
    val normalizedBaseUrl = baseUrl.trimEnd('/')
    proxyPlans.forEachIndexed { index, plan ->
        val proxyUrl = "$normalizedBaseUrl/r/$index/"
        proxyToCanonicalUrl[proxyUrl] = plan.canonicalUrlForGeneratedOutput
        plan.canonicalAliases.forEach { canonicalUrl ->
            canonicalToProxyUrl[canonicalUrl] = proxyUrl
        }
    }
    return LocalMavenProxyRepositoryMappings(
        proxyToCanonicalUrl = proxyToCanonicalUrl,
        canonicalToProxyUrl = canonicalToProxyUrl
    )
}

private data class LocalMavenRepositoryProxyPlan(
    val origin: LocalMavenProxyOrigin,
    val canonicalAliases: Set<String>,
    val canonicalUrlForGeneratedOutput: String,
)

private fun localMavenRepositoryProxyPlans(
    repositories: List<RepositoryWithAuth>,
    canonicalRepositoryUrls: Set<String>,
): List<LocalMavenRepositoryProxyPlan> {
    val repositoryPlans = repositories.map { repository ->
        val aliases = canonicalUrlAliases(repository)
        LocalMavenRepositoryProxyPlan(
            origin = toProxyOrigin(repository),
            canonicalAliases = aliases,
            canonicalUrlForGeneratedOutput = canonicalUrlForGeneratedOutput(
                repository = repository,
                canonicalRepositoryUrls = canonicalRepositoryUrls
            )
        )
    }
    val coveredAliases = repositoryPlans
        .flatMap { plan -> plan.canonicalAliases }
        .toSet()
    val externalPlans = (canonicalRepositoryUrls - coveredAliases)
        .sorted()
        .mapIndexed { index, canonicalUrl ->
            LocalMavenRepositoryProxyPlan(
                origin = LocalMavenProxyOrigin(
                    name = "external-$index",
                    url = canonicalUrl
                ),
                canonicalAliases = setOf(canonicalUrl),
                canonicalUrlForGeneratedOutput = canonicalUrl
            )
        }
    return repositoryPlans + externalPlans
}

private fun canonicalUrlForGeneratedOutput(
    repository: RepositoryWithAuth,
    canonicalRepositoryUrls: Set<String>,
): String {
    val credentialedUrl = credentialedUrl(repository)
    return when {
        credentialedUrl != null && credentialedUrl in canonicalRepositoryUrls -> credentialedUrl
        repository.url in canonicalRepositoryUrls -> repository.url
        credentialedUrl != null && canonicalRepositoryUrls.isEmpty() -> credentialedUrl
        else -> repository.url
    }
}

private fun canonicalUrlAliases(repository: RepositoryWithAuth): Set<String> =
    listOfNotNull(repository.url, credentialedUrl(repository)).toSet()

private fun credentialedUrl(repository: RepositoryWithAuth): String? =
    when (val repositoryAuth = repository.auth) {
        is RepositoryAuth.Basic -> mavenRepositoryUrlWithBasicCredentials(
            url = repository.url,
            username = repositoryAuth.username,
            password = repositoryAuth.password
        )

        is RepositoryAuth.Header,
        RepositoryAuth.None -> null
    }
