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

import com.google.common.io.Files
import com.grab.grazel.maven.MavenCoordinates
import com.grab.grazel.maven.MavenPath
import com.grab.grazel.maven.isConcreteMavenArtifactPath
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
    val knownComponentGavs: Set<String>,
    val metadataOnlyGavs: Set<String>,
    val pomFileResolver: PomFileResolver,
)

internal class LocalMavenResolvedFactsBuilder(
    private val project: Project
) {
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
            MavenCoordinates(
                group = component.group,
                module = component.module,
                version = component.version
            ).mavenRelativePaths(artifact.file.name).forEach { path ->
                index.putIfAbsent(path, artifact.file)
            }
        }
        return index
    }
}

internal class GradleModuleCacheFileIndexBuilder(
    private val fileResolver: GradleModuleCacheFileResolver
) {
    constructor(gradleUserHomeDir: File) : this(GradleModuleCacheFileResolver(gradleUserHomeDir))

    fun index(gavs: Iterable<String>): Map<String, File> {
        val index = sortedMapOf<String, File>()
        gavs
            .map { gav -> MavenCoordinates.parse(gav) }
            .sortedWith(
                compareBy<MavenCoordinates> { coordinates -> coordinates.group }
                    .thenBy { coordinates -> coordinates.module }
                    .thenBy { coordinates -> coordinates.version }
            )
            .forEach { coordinates ->
                fileResolver.cacheFiles(coordinates)
                    .forEach { file ->
                        coordinates.mavenRelativePaths(file.name).forEach { path ->
                            putMavenFile(index = index, path = path, file = file)
                        }
                    }
            }
        return index
    }
}

internal class GradleModuleCacheFileResolver(
    gradleUserHomeDir: File
) {
    private val modulesCacheRoot: File =
        gradleUserHomeDir.resolve("caches/modules-2/files-2.1")
    private val artifactPathCache = ConcurrentHashMap<String, ArtifactPathResolution>()
    private val cacheFilesByCoordinates = ConcurrentHashMap<MavenCoordinates, List<File>>()

    fun resolveArtifact(path: String): File? {
        return when (val resolution = artifactPathCache.computeIfAbsent(path, ::resolveArtifactUncached)) {
            is ArtifactPathResolution.Found -> resolution.file
            ArtifactPathResolution.Missing -> null
        }
    }

    private fun resolveArtifactUncached(path: String): ArtifactPathResolution {
        val mavenPath = MavenPath.parse(path) ?: return ArtifactPathResolution.Missing
        val matchingFiles = cacheFiles(mavenPath.coordinates)
            .filter { file ->
                file.name == mavenPath.fileName ||
                    path in mavenPath.coordinates.mavenRelativePaths(file.name)
            }
        return artifactPathResolution(singleMavenFileOrNull(files = matchingFiles, path = path))
    }

    fun cacheFiles(coordinates: MavenCoordinates): List<File> {
        return cacheFilesByCoordinates.computeIfAbsent(coordinates, ::cacheFilesUncached)
    }

    fun cacheDirectory(coordinates: MavenCoordinates): File =
        coordinates.cacheDirectory(modulesCacheRoot)

    private fun cacheFilesUncached(coordinates: MavenCoordinates): List<File> =
        cacheDirectory(coordinates)
            .listFiles()
            .orEmpty()
            .filter { hashDirectory -> hashDirectory.isDirectory }
            .sortedBy { hashDirectory -> hashDirectory.name }
            .flatMap { hashDirectory ->
                hashDirectory
                    .listFiles()
                    .orEmpty()
                    .filter { file -> file.isFile }
                    .sortedBy { file -> file.name }
            }

    private sealed interface ArtifactPathResolution {
        data class Found(val file: File) : ArtifactPathResolution
        object Missing : ArtifactPathResolution
    }

    private fun artifactPathResolution(file: File?): ArtifactPathResolution =
        file?.let { ArtifactPathResolution.Found(it) } ?: ArtifactPathResolution.Missing
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

private fun putMavenFile(
    index: MutableMap<String, File>,
    path: String,
    file: File,
) {
    val existing = index[path]
    when {
        existing == null -> index[path] = file
        existing == file -> Unit
        sameFileContent(existing, file) -> Unit
        else -> error(
            "Gradle cache has multiple different files for Maven path $path: " +
                "${existing.absolutePath} and ${file.absolutePath}"
        )
    }
}

private fun singleMavenFileOrNull(
    files: List<File>,
    path: String,
): File? {
    return files.fold<File, File?>(null) { selected, file ->
        when {
            selected == null -> file
            selected == file -> selected
            sameFileContent(selected, file) -> selected
            else -> error(
                "Gradle cache has multiple different files for Maven path $path: " +
                    "${selected.absolutePath} and ${file.absolutePath}"
            )
        }
    }
}

private fun sameFileContent(first: File, second: File): Boolean {
    return Files.equal(first, second)
}

internal fun interface PomFileResolver {
    fun resolvePom(gav: String): File?
}

internal class GradlePomFileResolver(
    private val componentIdsByGav: Map<String, ComponentIdentifier>,
    private val pomArtifactQuery: PomArtifactQuery,
    private val pomCacheLookup: PomCacheLookup = PomCacheLookup { null },
) : PomFileResolver {
    private val pomFilesByGav = ConcurrentHashMap<String, PomFileResolution>()

    override fun resolvePom(gav: String): File? {
        return when (val resolution = pomFilesByGav.computeIfAbsent(gav, ::resolvePomUncached)) {
            is PomFileResolution.Found -> resolution.file
            PomFileResolution.Missing -> null
        }
    }

    private fun resolvePomUncached(gav: String): PomFileResolution {
        val cachedPom = pomCacheLookup.findPomFile(gav)
            ?.takeIf { pom -> pom.exists() }
        val componentPom = cachedPom ?: componentIdsByGav[gav]
            ?.let { componentId ->
                runCatching { pomArtifactQuery.findPomFile(componentId) }.getOrNull()
            }
        return if (componentPom != null && componentPom.exists()) {
            PomFileResolution.Found(componentPom)
        } else {
            PomFileResolution.Missing
        }
    }

    private sealed interface PomFileResolution {
        data class Found(val file: File) : PomFileResolution
        object Missing : PomFileResolution
    }

    companion object {
        fun from(
            project: Project,
            componentIdsByGav: Map<String, ComponentIdentifier>,
            moduleCacheFileResolver: GradleModuleCacheFileResolver,
        ): GradlePomFileResolver {
            return GradlePomFileResolver(
                componentIdsByGav = componentIdsByGav,
                pomArtifactQuery = PomArtifactQuery { componentId ->
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
                        .mapNotNull(moduleCacheFileResolver::resolveArtifact)
                        .firstOrNull()
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
