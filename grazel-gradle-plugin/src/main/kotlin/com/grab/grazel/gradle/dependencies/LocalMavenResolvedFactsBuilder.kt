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
import com.grab.grazel.maven.MavenPath
import com.grab.grazel.maven.isConcreteMavenArtifactPath
import com.grab.grazel.proxy.LocalMavenResolvedFacts
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import java.io.File

internal class LocalMavenResolvedFactsBuilder(
    private val project: Project
) {
    /**
     * Orchestrates the facts the proxy trusts as "Gradle-resolved". Order matters: the
     * component index and the resolved-artifact index are built first from live configuration
     * resolution; only the *gap* between all known GAVs (resolved components plus
     * [additionalGavs]) and what the artifact index already covers is looked up in Gradle's
     * module cache on disk, since that lookup is comparatively expensive and unnecessary for
     * GAVs already resolved through the configuration graph. The module-cache results are then
     * merged in as a fallback (never overriding an already-resolved artifact), and
     * metadata-only GAVs are derived last, from the *merged* index, so a component with no
     * resolvable artifact anywhere is correctly classified as metadata-only rather than
     * missing.
     */
    fun build(
        configurations: Iterable<Configuration>,
        additionalGavs: Iterable<String> = emptyList(),
    ): LocalMavenResolvedFacts {
        val configurationList = configurations.toList()
        val componentIdsByGav = ResolvedComponentIndexBuilder.index(configurationList)
        val knownComponentGavs = componentIdsByGav.keys.toSortedSet()
        val indexGavs = (knownComponentGavs + additionalGavs).toSortedSet()
        val artifactIndex = ResolvedArtifactIndexBuilder.indexConfigurations(configurationList)
        val artifactIndexGavs = artifactIndex.keys
            .asSequence()
            .mapNotNull { path -> MavenPath.parse(path)?.coordinates?.gav }
            .toSet()
        val moduleCacheFileResolver = GradleModuleCacheFileResolver(project.gradle.gradleUserHomeDir)
        val moduleCacheIndex = GradleModuleCacheFileIndexBuilder(moduleCacheFileResolver)
            .index(indexGavs - artifactIndexGavs)
        val mergedArtifactIndex = mergeArtifactIndexes(
            primary = artifactIndex,
            fallback = moduleCacheIndex
        )
        val metadataOnlyGavs = metadataOnlyComponentGavs(
            knownComponentGavs = knownComponentGavs,
            artifactPaths = mergedArtifactIndex.keys
        )
        return LocalMavenResolvedFacts(
            artifactIndex = mergedArtifactIndex,
            knownComponentGavs = knownComponentGavs,
            metadataOnlyGavs = metadataOnlyGavs,
            pomFileResolver = GradlePomFileResolver.from(
                project = project,
                componentIdsByGav = componentIdsByGav,
                moduleCacheFileResolver = moduleCacheFileResolver
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
                .flatMap { configuration -> configuration.externalModuleArtifacts() }
                .asIterable()
        )
    }

    /**
     * Indexes resolved artifacts by every Maven path they could be requested under, using
     * `putIfAbsent` so the first configuration to contribute a given path wins. Since
     * [indexConfigurations] feeds configurations here in a fixed (name-sorted) order, that sort
     * order is what actually determines which file "wins" when multiple configurations resolve
     * conflicting files for the same Maven path — this is deliberate first-writer-wins
     * semantics, not an arbitrary iteration artifact.
     */
    fun indexArtifacts(artifacts: Iterable<ResolvedArtifactResult>): Map<String, File> {
        val index = sortedMapOf<String, File>()
        artifacts.forEach { artifact ->
            val component = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                ?: return@forEach
            component.toMavenCoordinates()
                .mavenRelativePaths(artifact.file.name)
                .forEach { path ->
                index.putIfAbsent(path, artifact.file)
            }
        }
        return index
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

private fun mergeArtifactIndexes(
    primary: Map<String, File>,
    fallback: Map<String, File>,
): Map<String, File> {
    val merged = primary.toSortedMap()
    fallback.forEach { (path, file) ->
        putMavenFile(index = merged, path = path, file = file)
    }
    return merged
}

internal fun metadataOnlyComponentGavs(
    knownComponentGavs: Set<String>,
    artifactPaths: Set<String>,
): Set<String> {
    val concreteArtifactGavs = artifactPaths
        .asSequence()
        .filter(::isConcreteMavenArtifactPath)
        .mapNotNull { path -> MavenPath.parse(path)?.coordinates?.gav }
        .toSet()
    return knownComponentGavs
        .asSequence()
        .filterNot { gav -> gav in concreteArtifactGavs }
        .toSortedSet()
}
