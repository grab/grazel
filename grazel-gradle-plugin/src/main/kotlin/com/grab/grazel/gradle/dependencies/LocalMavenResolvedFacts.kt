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

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal data class LocalMavenResolvedFacts(
    val artifactIndex: Map<String, File>,
    val pomFileResolver: PomFileResolver,
)

internal interface PomFileResolver {
    fun resolvePom(gav: String): File?
}

internal class LocalMavenResolvedFactsBuilder(
    private val project: Project
) {
    fun build(configurations: Iterable<Configuration>): LocalMavenResolvedFacts {
        val configurationList = configurations.toList()
        val componentIdsByGav = ResolvedComponentIndexBuilder.index(configurationList)
        return LocalMavenResolvedFacts(
            artifactIndex = ResolvedArtifactIndexBuilder.indexConfigurations(configurationList),
            pomFileResolver = GradlePomFileResolver.from(
                project = project,
                componentIdsByGav = componentIdsByGav
            )
        )
    }
}

internal object ResolvedArtifactIndexBuilder {
    fun indexConfigurations(configurations: Iterable<Configuration>): Map<String, File> {
        return indexArtifacts(
            configurations
                .sortedBy { configuration -> configuration.name }
                .asSequence()
                .flatMap { configuration ->
                    configuration
                        .incoming
                        .artifactView {
                            isLenient = true
                            componentFilter { id -> id is ModuleComponentIdentifier }
                        }
                        .artifacts
                }
                .asIterable()
        )
    }

    fun indexArtifacts(artifacts: Iterable<ResolvedArtifactResult>): Map<String, File> {
        val index = sortedMapOf<String, File>()
        artifacts.forEach { artifact ->
            val component = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                ?: return@forEach
            val path = mavenRelativePath(
                component = component,
                fileName = artifact.file.name
            )
            index.putIfAbsent(path, artifact.file)
        }
        return index
    }

    private fun mavenRelativePath(
        component: ModuleComponentIdentifier,
        fileName: String
    ): String {
        return listOf(
            component.group.replace('.', '/'),
            component.module,
            component.version,
            fileName
        ).joinToString("/")
    }
}

internal object ResolvedComponentIndexBuilder {
    fun index(configurations: Iterable<Configuration>): Map<String, ComponentIdentifier> {
        return indexComponents(
            configurations
                .sortedBy { configuration -> configuration.name }
                .asSequence()
                .flatMap { configuration ->
                    configuration.incoming.resolutionResult.allComponents.asSequence()
                }
                .asIterable()
        )
    }

    fun indexComponents(components: Iterable<ResolvedComponentResult>): Map<String, ComponentIdentifier> {
        val index = sortedMapOf<String, ComponentIdentifier>()
        components
            .asSequence()
            .filter { component -> component.id !is ProjectComponentIdentifier }
            .forEach { component ->
                val moduleVersion = component.moduleVersion ?: return@forEach
                val gav = "${moduleVersion.group}:${moduleVersion.name}:${moduleVersion.version}"
                index.putIfAbsent(gav, component.id)
            }
        return index
    }
}

internal class GradlePomFileResolver(
    private val componentIdsByGav: Map<String, ComponentIdentifier>,
    private val queryPom: (ComponentIdentifier) -> File?,
) : PomFileResolver {
    private val cache = ConcurrentHashMap<String, PomResolution>()

    override fun resolvePom(gav: String): File? {
        val componentId = componentIdsByGav[gav] ?: return null
        return when (
            val resolution = cache.computeIfAbsent(gav) {
                queryPom(componentId)
                    ?.takeIf { file -> file.exists() }
                    ?.let { file -> PomResolution.Found(file) }
                    ?: PomResolution.Missing
            }
        ) {
            is PomResolution.Found -> resolution.file
            PomResolution.Missing -> error(
                "Gradle resolved component $gav did not provide a Maven POM"
            )
        }
    }

    private sealed interface PomResolution {
        data class Found(val file: File) : PomResolution
        object Missing : PomResolution
    }

    companion object {
        fun from(
            project: Project,
            componentIdsByGav: Map<String, ComponentIdentifier>
        ): GradlePomFileResolver {
            return GradlePomFileResolver(componentIdsByGav) { componentId ->
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
            }
        }
    }
}
