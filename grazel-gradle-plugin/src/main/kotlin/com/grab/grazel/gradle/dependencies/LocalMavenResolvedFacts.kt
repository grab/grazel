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
    val artifactFileResolver: MavenArtifactFileResolver,
    val knownComponentGavs: Set<String>,
    val metadataOnlyGavs: Set<String>,
    val pomFileResolver: PomFileResolver,
)

internal interface MavenArtifactFileResolver {
    fun resolveArtifact(path: String): File?

    object None : MavenArtifactFileResolver {
        override fun resolveArtifact(path: String): File? = null
    }
}

internal interface PomFileResolver {
    fun resolvePom(gav: String): File?
}

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
        val moduleCacheFileResolver = GradleModuleCacheFileResolver(project.gradle.gradleUserHomeDir)
        val artifactIndexGavs = artifactIndex
            .keys
            .asSequence()
            .filter(::isConcreteArtifactPath)
            .mapNotNull { path -> MavenPath.parse(path)?.coordinates?.gav }
            .toSet()
        val moduleCacheIndex = GradleModuleCacheFileIndexBuilder(moduleCacheFileResolver)
            .index(indexGavs - artifactIndexGavs)
        val mergedArtifactIndex = mergeArtifactIndexes(
            primary = artifactIndex,
            fallback = moduleCacheIndex
        )
        return LocalMavenResolvedFacts(
            artifactIndex = mergedArtifactIndex,
            artifactFileResolver = moduleCacheFileResolver,
            knownComponentGavs = knownComponentGavs,
            metadataOnlyGavs = metadataOnlyGavs(
                gavs = indexGavs,
                artifactPaths = mergedArtifactIndex.keys
            ),
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
                            index.putMavenFile(path, file)
                        }
                    }
            }
        return index
    }
}

internal class GradleModuleCacheFileResolver(
    gradleUserHomeDir: File
) : MavenArtifactFileResolver {
    private val modulesCacheRoot: File =
        gradleUserHomeDir.resolve("caches/modules-2/files-2.1")
    private val artifactPathCache = ConcurrentHashMap<String, ArtifactPathResolution>()
    private val cacheFilesByCoordinates = ConcurrentHashMap<MavenCoordinates, List<File>>()

    override fun resolveArtifact(path: String): File? {
        return when (val resolution = artifactPathCache.computeIfAbsent(path, ::resolveArtifactUncached)) {
            is ArtifactPathResolution.Found -> resolution.file
            ArtifactPathResolution.Missing -> null
        }
    }

    private fun resolveArtifactUncached(path: String): ArtifactPathResolution {
        val mavenPath = MavenPath.parse(path) ?: return ArtifactPathResolution.Missing
        return cacheFiles(mavenPath.coordinates)
            .filter { file ->
                file.name == mavenPath.fileName ||
                    path in mavenPath.coordinates.mavenRelativePaths(file.name)
            }
            .singleMavenFileOrNull(path)
            .toArtifactPathResolution()
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

    private fun File?.toArtifactPathResolution(): ArtifactPathResolution =
        this?.let { file -> ArtifactPathResolution.Found(file) } ?: ArtifactPathResolution.Missing
}

internal data class MavenPath(
    val coordinates: MavenCoordinates,
    val fileName: String,
) {
    companion object {
        fun parse(path: String): MavenPath? {
            val parts = path.split("/")
            if (parts.size < 4) return null
            return MavenPath(
                coordinates = MavenCoordinates(
                    group = parts.dropLast(3).joinToString("."),
                    module = parts[parts.lastIndex - 2],
                    version = parts[parts.lastIndex - 1],
                ),
                fileName = parts.last()
            )
        }
    }
}

internal data class MavenCoordinates(
    val group: String,
    val module: String,
    val version: String,
) {
    val gav: String = "$group:$module:$version"

    fun mavenRelativePaths(fileName: String): Set<String> {
        val physicalPath = mavenRelativePath(fileName)
        val canonicalPath = mavenRelativePath(canonicalMavenFileName(fileName))
        return setOf(physicalPath, canonicalPath)
    }

    fun cacheDirectory(modulesCacheRoot: File): File {
        return listOf(modulesCacheRoot.path, group, module, version)
            .joinToString(File.separator)
            .let(::File)
    }

    private fun canonicalMavenFileName(fileName: String): String {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            .takeIf { extension -> extension.isNotBlank() }
            ?: return fileName
        val mavenPrefix = "$module-$version"
        val baseName = fileName.removeSuffix(".$extension")
        val classifier = baseName
            .takeIf { name -> name.startsWith("$mavenPrefix-") }
            ?.removePrefix("$mavenPrefix-")
            ?.takeIf { value -> value.isNotBlank() }
        return buildString {
            append(mavenPrefix)
            if (classifier != null) {
                append('-')
                append(classifier)
            }
            append('.')
            append(extension)
        }
    }

    private fun mavenRelativePath(fileName: String): String {
        return listOf(
            group.replace('.', '/'),
            module,
            version,
            fileName
        ).joinToString("/")
    }

    companion object {
        fun parse(gav: String): MavenCoordinates {
            val parts = gav.split(":")
            require(parts.size == 3) { "Expected group:name:version coordinate, got $gav" }
            return MavenCoordinates(
                group = parts[0],
                module = parts[1],
                version = parts[2]
            )
        }
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
        merged.putMavenFile(path, file)
    }
    return merged
}

private fun metadataOnlyGavs(
    gavs: Set<String>,
    artifactPaths: Set<String>,
): Set<String> {
    val concreteArtifactGavs = artifactPaths
        .asSequence()
        .filter(::isConcreteArtifactPath)
        .mapNotNull { path -> MavenPath.parse(path)?.coordinates?.gav }
        .toSet()
    return gavs
        .asSequence()
        .filterNot { gav -> gav in concreteArtifactGavs }
        .toSortedSet()
}

internal fun isConcreteArtifactPath(path: String): Boolean {
    if (path.endsWith(".pom") || path.endsWith(".module")) return false
    if (path.endsWith(".sha1") || path.endsWith(".md5") || path.endsWith(".sha256")) return false
    if (path.endsWith("maven-metadata.xml")) return false
    return MavenPath.parse(path) != null
}

private fun MutableMap<String, File>.putMavenFile(path: String, file: File) {
    val existing = this[path]
    when {
        existing == null -> this[path] = file
        existing == file -> Unit
        existing.sameContentAs(file) -> Unit
        else -> error(
            "Gradle cache has multiple different files for Maven path $path: " +
                "${existing.absolutePath} and ${file.absolutePath}"
        )
    }
}

private fun List<File>.singleMavenFileOrNull(path: String): File? {
    return fold<File, File?>(null) { selected, file ->
        when {
            selected == null -> file
            selected == file -> selected
            selected.sameContentAs(file) -> selected
            else -> error(
                "Gradle cache has multiple different files for Maven path $path: " +
                    "${selected.absolutePath} and ${file.absolutePath}"
            )
        }
    }
}

private fun File.sameContentAs(other: File): Boolean {
    return Files.equal(this, other)
}

internal class GradlePomFileResolver(
    private val componentIdsByGav: Map<String, ComponentIdentifier>,
    private val queryPom: (ComponentIdentifier) -> File?,
) : PomFileResolver {
    private val cache = ConcurrentHashMap<String, PomResolution>()

    override fun resolvePom(gav: String): File? {
        val componentId = componentIdsByGav[gav] ?: return null
        return when (val resolution = cache.computeIfAbsent(gav) { queryPom(componentId).toPomResolution() }) {
            is PomResolution.Found -> resolution.file
            PomResolution.Missing -> null
        }
    }

    private sealed interface PomResolution {
        data class Found(val file: File) : PomResolution
        object Missing : PomResolution
    }

    private fun File?.toPomResolution(): PomResolution =
        this
            ?.takeIf { file -> file.exists() }
            ?.let { file -> PomResolution.Found(file) }
            ?: PomResolution.Missing

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
