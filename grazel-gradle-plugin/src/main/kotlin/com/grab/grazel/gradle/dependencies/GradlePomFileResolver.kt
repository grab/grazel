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

import com.grab.grazel.maven.MavenCoordinates
import com.grab.grazel.proxy.PomFileResolution
import com.grab.grazel.proxy.PomFileResolver
import com.grab.grazel.proxy.LocalMavenProxyServer
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal class GradlePomFileResolver(
    private val componentIdsByGav: Map<String, ComponentIdentifier>,
    private val pomArtifactQuery: PomArtifactQuery,
    private val pomCacheLookup: PomCacheLookup = PomCacheLookup { null },
) : PomFileResolver {
    private val pomFilesByGav = ConcurrentHashMap<String, PomFileResolution>()

    override fun resolvePom(gav: String): PomFileResolution =
        pomFilesByGav.computeIfAbsent(gav, ::resolvePomUncached)

    /**
     * Chains a cheap module-cache lookup before falling back to Gradle's artifact resolution
     * query, since the latter can trigger network/POM resolution and is comparatively
     * expensive. An exception from the query is deliberately captured and mapped to
     * [PomFileResolution.Unavailable] rather than rethrown or treated as [PomFileResolution.Unknown]:
     * the proxy server ([LocalMavenProxyServer.servePom]) hard-fails on `Unavailable` for known
     * components but silently falls through to origin on `Unknown`, so the distinction changes
     * observable behavior.
     */
    private fun resolvePomUncached(gav: String): PomFileResolution {
        val cachedPom = pomCacheLookup.findPomFile(gav)
            ?.takeIf { pom -> pom.exists() }
        if (cachedPom != null) {
            return PomFileResolution.Found(cachedPom)
        }
        val componentId = componentIdsByGav[gav] ?: return PomFileResolution.Unknown
        val componentPom = runCatching {
            pomArtifactQuery.findPomFile(componentId)
        }.getOrElse { exception ->
            return PomFileResolution.Unavailable(
                gav = gav,
                message = "Failed to resolve Gradle Maven POM for known component $gav: " +
                    exception.message.orEmpty()
            )
        }
        return if (componentPom != null && componentPom.exists()) {
            PomFileResolution.Found(componentPom)
        } else {
            PomFileResolution.Unavailable(
                gav = gav,
                message = "Missing Gradle-resolved POM for known component $gav"
            )
        }
    }

    companion object {
        fun from(
            project: Project,
            componentIdsByGav: Map<String, ComponentIdentifier>,
            moduleCacheFileResolver: GradleModuleCacheFileResolver,
        ): GradlePomFileResolver {
            return GradlePomFileResolver(
                componentIdsByGav = componentIdsByGav,
                pomArtifactQuery = { componentId ->
                    project
                        .dependencies
                        .createArtifactResolutionQuery()
                        .forComponents(componentId)
                        .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
                        .execute()
                        .resolvedComponents
                        .asSequence()
                        .flatMap { component ->
                            component
                                .getArtifacts(MavenPomArtifact::class.java)
                                .asSequence()
                        }
                        .filterIsInstance<ResolvedArtifactResult>()
                        .firstOrNull()
                        ?.file
                        ?.takeIf { file -> file.exists() }
                },
                pomCacheLookup = PomCacheLookup { gav ->
                    val coordinates = MavenCoordinates.parse(gav)
                    val pomFileName = "${coordinates.module}-${coordinates.version}.pom"
                    coordinates
                        .mavenRelativePaths(pomFileName)
                        .asSequence()
                        .firstNotNullOfOrNull(moduleCacheFileResolver::resolveArtifact)
                }
            )
        }
    }
}

internal fun interface PomArtifactQuery {
    fun findPomFile(componentId: ComponentIdentifier): File?
}

internal fun interface PomCacheLookup {
    fun findPomFile(gav: String): File?
}
