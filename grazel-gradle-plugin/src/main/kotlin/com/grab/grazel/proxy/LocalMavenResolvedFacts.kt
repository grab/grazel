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

    /**
     * Gradle's `modules-2` cache stores each artifact under a hash-named subdirectory
     * (`<group>/<module>/<version>/<hash>/<file>`) with no guaranteed enumeration order, so
     * both the hash directories and the files within each are explicitly sorted by name here
     * to give deterministic candidate ordering — required so that matching logic (e.g.
     * [singleMavenFileOrNull]) behaves reproducibly across JVM/filesystem runs rather than
     * depending on incidental directory-listing order.
     */
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

/**
 * Enforces that a single Maven path can only ever map to one physical file: a new mapping is
 * accepted only if there's no existing entry, or the existing entry is the same file (by
 * identity or, failing that, byte-for-byte content via [Files.equal]). Any genuine divergence
 * hard-fails rather than silently picking a winner, since silently choosing one would mean the
 * proxy could serve different bytes for the same path across requests depending on unrelated
 * indexing order.
 */
private fun putMavenFile(
    index: MutableMap<String, File>,
    path: String,
    file: File,
) {
    val existing = index[path]
    when {
        existing == null -> index[path] = file
        existing == file -> Unit
        Files.equal(existing, file) -> Unit
        else -> error(
            "Gradle cache has multiple different files for Maven path $path: " +
                "${existing.absolutePath} and ${file.absolutePath}"
        )
    }
}

/**
 * Same single-file-per-path invariant as [putMavenFile], applied via fold to a candidate list
 * of module-cache matches for one path: any two candidates that aren't identical or
 * byte-equal ([Files.equal]) are treated as a genuine conflict and hard-fail rather than
 * arbitrarily selecting one, since the caller ([resolveArtifactUncached]) needs a single
 * unambiguous file to serve for that path.
 */
private fun singleMavenFileOrNull(
    files: List<File>,
    path: String,
): File? {
    return files.fold<File, File?>(null) { selected, file ->
        when {
            selected == null -> file
            selected == file -> selected
            Files.equal(selected, file) -> selected
            else -> error(
                "Gradle cache has multiple different files for Maven path $path: " +
                    "${selected.absolutePath} and ${file.absolutePath}"
            )
        }
    }
}

internal fun interface PomFileResolver {
    fun resolvePom(gav: String): PomFileResolution
}

internal sealed interface PomFileResolution {
    data class Found(val file: File) : PomFileResolution
    data class Unavailable(val gav: String, val message: String) : PomFileResolution
    object Unknown : PomFileResolution
}

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
