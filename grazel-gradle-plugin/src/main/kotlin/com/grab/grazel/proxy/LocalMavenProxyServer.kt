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

import com.grab.grazel.gradle.RepositoryAuth
import com.grab.grazel.maven.LocalMavenResolutionStats
import com.grab.grazel.maven.MavenPath
import com.grab.grazel.maven.isConcreteMavenArtifactPath
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempFile
import kotlin.io.path.moveTo

internal data class LocalMavenProxyOrigin(
    val name: String,
    val url: String,
    val auth: RepositoryAuth = RepositoryAuth.None
)

internal class LocalMavenProxyServer(
    private val cacheDir: File,
    private val origins: List<LocalMavenProxyOrigin>,
) : AutoCloseable {
    private val lock = Any()
    private val originMissMutexes = ConcurrentHashMap<String, Mutex>()
    private val counters = LocalMavenProxyCounters()
    private val client = HttpClient(ClientCIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000
        }
    }
    private var artifactIndex: Map<String, File> = emptyMap()
    private var knownComponentGavs: Set<String> = emptySet()
    private var metadataOnlyGavs: Set<String> = emptySet()
    private var indexedArtifactGavs: Set<String> = emptySet()
    private var knownMainArtifactExtensionsByGav: Map<String, Set<String>> = emptyMap()
    private var pomFileResolver: PomFileResolver = PomFileResolver { PomFileResolution.Unknown }
    private var engine: ApplicationEngine? = null
    private var boundBaseUrl: String? = null
    private val warnedFallthroughGavs = ConcurrentHashMap.newKeySet<String>()

    /**
     * Swaps in a fresh snapshot of Gradle-resolved facts and derives the two secondary
     * indices ([indexedArtifactGavs], [knownMainArtifactExtensionsByGav]) that [serve] and
     * [servePom] rely on for correctness: the former gates which GAVs may be POM-served from
     * Gradle at all, the latter drives the alternate-artifact-probe short-circuit in
     * [isKnownAlternateArtifactProbe]. Must run under [lock] since requests may be served
     * concurrently against the previous snapshot until this completes.
     */
    fun configure(
        artifactIndex: Map<String, File>,
        knownComponentGavs: Set<String>,
        metadataOnlyGavs: Set<String>,
        pomFileResolver: PomFileResolver,
    ) {
        synchronized(lock) {
            this.artifactIndex = artifactIndex
            this.knownComponentGavs = knownComponentGavs
            this.metadataOnlyGavs = metadataOnlyGavs
            this.indexedArtifactGavs = artifactIndex.keys
                .asSequence()
                .mapNotNull(::concreteGavFromMavenPathOrNull)
                .toSet()
            this.knownMainArtifactExtensionsByGav = artifactIndex.keys
                .asSequence()
                .mapNotNull(::mainArtifactExtensionByGav)
                .groupBy(
                    keySelector = { (gav, _) -> gav },
                    valueTransform = { (_, extension) -> extension }
                )
                .mapValues { (_, extensions) -> extensions.toSet() }
            this.pomFileResolver = pomFileResolver
        }
    }

    fun baseUrl(): String {
        return synchronized(lock) {
            boundBaseUrl ?: startServerLocked()
        }
    }

    fun stats(): LocalMavenResolutionStats = counters.snapshot()

    private fun startServerLocked(): String {
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            routing {
                get("/r/{repo}/{path...}") {
                    val repoIndex = call.parameters["repo"]?.toIntOrNull()
                    val path = call.parameters.getAll("path")
                        .orEmpty()
                        .joinToString("/")
                    val response = try {
                        serve(repoIndex, path)
                    } catch (exception: Exception) {
                        counters.requestFailures.incrementAndGet()
                        ServedResponse.Bytes(
                            status = HttpStatusCode.InternalServerError,
                            bytes = exception.message.orEmpty().toByteArray()
                        )
                    }
                    when (response) {
                        is ServedResponse.Bytes -> call.respondBytes(
                            bytes = response.bytes,
                            status = response.status
                        )

                        is ServedResponse.File -> call.respondFile(response.file)
                    }
                }
            }
        }.start(wait = false)
        engine = server
        val port = runBlocking { server.resolvedConnectors().single().port }
        return "http://127.0.0.1:$port".also { boundBaseUrl = it }
    }

    /**
     * Central dispatch for a proxied Maven request: local-first, origin-fallthrough for
     * everything else. Checksums and POMs are handled first since coursier probes them
     * independently of the artifact index; a Gradle-resolved artifact (exact index hit) always
     * wins over anything else; known-absent classifier/extension probes are rejected with a 404
     * before touching the network, since coursier issues those routinely and an origin
     * round-trip for each would be wasted work. Everything else - a concrete GAV Gradle marked
     * metadata-only, a concrete GAV belonging to a component Gradle resolved but didn't index
     * this exact artifact for, or a concrete GAV Gradle has no knowledge of at all - falls
     * through to [serveFromCacheOrOrigin]. No branch in this dispatch can fail a build.
     */
    private suspend fun serve(
        repoIndex: Int?,
        path: String,
        countContentHit: Boolean = true,
    ): ServedResponse {
        if (repoIndex == null || path.isBlank()) {
            return ServedResponse.Bytes(HttpStatusCode.NotFound, ByteArray(0))
        }
        if (isChecksumPath(path)) {
            return serveChecksum(repoIndex, path)
        }
        if (isPomPath(path)) {
            return servePom(repoIndex, path, countContentHit)
        }
        artifactIndex[path]?.let { file ->
            return fileResponse(
                status = HttpStatusCode.OK,
                file = file,
                countContentHit = countContentHit,
                onServed = { counters.artifactHits.incrementAndGet() }
            )
        }
        if (isMavenMetadataPath(path)) {
            return serveFromCacheOrOrigin(repoIndex, path, countContentHit)
        }
        val concreteGav = concreteGavFromMavenPathOrNull(path)
        if (isKnownAlternateArtifactProbe(path, concreteGav)) {
            counters.alternateArtifactMisses.incrementAndGet()
            return ServedResponse.Bytes(HttpStatusCode.NotFound, ByteArray(0))
        }
        if (concreteGav in metadataOnlyGavs) {
            return serveArtifactWithFallbackCounter(
                repoIndex, path, countContentHit, counters.metadataOnlyArtifactFallbacks
            )
        }
        if (concreteGav != null && concreteGav in knownComponentGavs) {
            warnKnownComponentFallthrough(concreteGav, path)
            return serveArtifactWithFallbackCounter(
                repoIndex, path, countContentHit, counters.knownComponentFallthroughs
            )
        }
        return serveFromCacheOrOrigin(repoIndex, path, countContentHit)
    }

    private fun warnKnownComponentFallthrough(gav: String, path: String) {
        if (warnedFallthroughGavs.add(gav)) {
            logger.warn(
                "Local Maven proxy: Gradle knows component {} but has no local artifact for {} — " +
                    "falling through to origin", gav, path
            )
        }
    }

    /**
     * Short-circuits coursier's routine "does this classifier/extension exist" probes without
     * an origin round-trip, based on the extensions actually seen for this GAV in
     * [knownMainArtifactExtensionsByGav]. Correctness depends entirely on that map having been
     * fully populated for this GAV in [configure] beforehand; an empty entry is treated as "no
     * information" (falls through rather than rejecting) rather than "known to have none".
     */
    private fun isKnownAlternateArtifactProbe(path: String, gav: String?): Boolean {
        if (gav == null) return false
        val knownExtensions = knownMainArtifactExtensionsByGav[gav].orEmpty()
        return knownExtensions.isNotEmpty() &&
            artifactExtension(path) !in knownExtensions
    }

    private suspend fun serveArtifactWithFallbackCounter(
        repoIndex: Int,
        path: String,
        countContentHit: Boolean,
        fallbackCounter: AtomicLong,
    ): ServedResponse {
        val response = serveFromCacheOrOrigin(repoIndex, path, countContentHit)
        if (countContentHit && response.status == HttpStatusCode.OK) {
            fallbackCounter.incrementAndGet()
        }
        return response
    }

    /**
     * Recurses into [serve] for the base artifact (with `countContentHit = false` so the base
     * fetch doesn't double-count stats already attributed to the checksum request) and hashes
     * the exact bytes/file returned, rather than trusting any origin-published checksum. This
     * keeps checksums byte-identical to whatever this proxy actually served, which matters
     * since Gradle-resolved artifacts may differ from origin.
     */
    private suspend fun serveChecksum(repoIndex: Int, checksumPath: String): ServedResponse {
        val basePath = checksumBasePath(checksumPath)
        val baseResponse = serve(repoIndex, basePath, countContentHit = false)
        if (baseResponse.status != HttpStatusCode.OK) {
            return baseResponse
        }
        counters.checksumHits.incrementAndGet()
        return ServedResponse.Bytes(
            status = HttpStatusCode.OK,
            bytes = digestResponse(checksumPath.substringAfterLast('.'), baseResponse)
                .toByteArray()
        )
    }

    /**
     * Layers two sources of truth for a POM, short-circuiting as soon as one resolves:
     * Gradle-resolved POM (only attempted if the GAV is in [indexedArtifactGavs], i.e. Gradle
     * actually resolved an artifact for it) via [pomFileResolver]. Everything else - a
     * [PomFileResolution.Unavailable] or missing/nonexistent [PomFileResolution.Found] file for a
     * fully [knownComponentGavs] member, and any other unresolved case - falls through to
     * [serveFromCacheOrOrigin], counted as a known-component fallthrough where applicable.
     */
    private suspend fun servePom(
        repoIndex: Int,
        path: String,
        countContentHit: Boolean,
    ): ServedResponse {
        val gav = gavFromMavenPath(path)
        val canServeGradleBackedPom = gav in indexedArtifactGavs
        val pomResolution = if (canServeGradleBackedPom) {
            pomFileResolver.resolvePom(gav)
        } else {
            PomFileResolution.Unknown
        }
        when (pomResolution) {
            is PomFileResolution.Found -> pomResolution.file
                .takeIf { pom -> pom.exists() }
                ?.let { pom ->
                    return fileResponse(
                        status = HttpStatusCode.OK,
                        file = pom,
                        countContentHit = countContentHit,
                        onServed = { counters.gradlePomHits.incrementAndGet() }
                    )
                }

            PomFileResolution.Unknown,
            is PomFileResolution.Unavailable -> Unit
        }
        if (canServeGradleBackedPom && gav in knownComponentGavs) {
            warnKnownComponentFallthrough(gav, path)
            return serveArtifactWithFallbackCounter(
                repoIndex, path, countContentHit, counters.knownComponentFallthroughs
            )
        }
        return serveFromCacheOrOrigin(repoIndex, path, countContentHit)
    }

    /**
     * Double-checked-locking around a per-`(repoIndex, path)` [Mutex] so concurrent requests
     * for the same missing path coalesce into a single origin fetch: the cache is checked
     * before acquiring the mutex (fast path), again immediately after acquiring it (in case a
     * racing request already wrote through while we waited), and a third time after the fetch
     * completes as a final belt-and-braces check before writing. The mutex is created lazily
     * per key and removed again in `finally` once released, so [originMissMutexes] only ever
     * holds entries for in-flight fetches rather than growing unbounded.
     */
    private suspend fun serveFromCacheOrOrigin(
        repoIndex: Int,
        path: String,
        countContentHit: Boolean,
    ): ServedResponse {
        val cachedFile = originCacheFile(repoIndex, path)
        if (cachedFile.exists()) {
            return fileResponse(
                status = HttpStatusCode.OK,
                file = cachedFile,
                countContentHit = countContentHit,
                onServed = { counters.writeThroughCacheHits.incrementAndGet() }
            )
        }
        val origin = origins.getOrNull(repoIndex)
            ?: return ServedResponse.Bytes(HttpStatusCode.NotFound, ByteArray(0))
        val originMissKey = "$repoIndex:$path"
        val originMissMutex = originMissMutexes.computeIfAbsent(originMissKey) { Mutex() }
        return try {
            originMissMutex.withLock {
                if (cachedFile.exists()) {
                    return fileResponse(
                        status = HttpStatusCode.OK,
                        file = cachedFile,
                        countContentHit = countContentHit,
                        onServed = { counters.writeThroughCacheHits.incrementAndGet() }
                    )
                }
                val originResponse = fetchOrigin(origin, path)
                if (cachedFile.exists()) {
                    return fileResponse(
                        status = HttpStatusCode.OK,
                        file = cachedFile,
                        countContentHit = countContentHit,
                        onServed = { counters.writeThroughCacheHits.incrementAndGet() }
                    )
                }
                if (originResponse.status != HttpStatusCode.OK) {
                    counters.originFailures.incrementAndGet()
                    return ServedResponse.Bytes(originResponse.status, ByteArray(0))
                }
                val bytes = originResponse.bytes
                writeThrough(cachedFile, bytes)
                counters.originFallbacks.incrementAndGet()
                if (countContentHit) {
                    counters.bytesServed.addAndGet(bytes.size.toLong())
                }
                ServedResponse.Bytes(HttpStatusCode.OK, bytes)
            }
        } finally {
            originMissMutexes.remove(originMissKey, originMissMutex)
        }
    }

    private fun originCacheFile(repoIndex: Int, path: String): File =
        File(File(cacheDir, repoIndex.toString()), path)

    private suspend fun fetchOrigin(
        origin: LocalMavenProxyOrigin,
        path: String,
    ): OriginResponse {
        val response = client.get("${origin.url.trimEnd('/')}/$path") {
            when (val auth = origin.auth) {
                is RepositoryAuth.Basic -> basicAuth(auth.username, auth.password)
                is RepositoryAuth.Header -> header(auth.name, auth.value)
                RepositoryAuth.None -> Unit
            }
        }
        return OriginResponse(
            status = response.status,
            bytes = if (response.status == HttpStatusCode.OK) {
                response.body()
            } else {
                ByteArray(0)
            }
        )
    }

    private fun fileResponse(
        status: HttpStatusCode,
        file: File,
        countContentHit: Boolean,
        onServed: () -> Unit,
    ): ServedResponse {
        if (countContentHit) {
            onServed()
            counters.bytesServed.addAndGet(file.length())
        }
        return ServedResponse.File(status, file)
    }

    /**
     * Writes via a sibling temp file plus an atomic move-with-overwrite so a reader that races
     * with an in-progress origin fetch (from another proxy instance or a prior process) never
     * observes a partially-written cache file.
     */
    private fun writeThrough(file: File, bytes: ByteArray) {
        file.parentFile.mkdirs()
        val temp = createTempFile(
            directory = file.parentFile.toPath(),
            prefix = "${file.name}.",
            suffix = ".tmp"
        )
        temp.toFile().writeBytes(bytes)
        temp.moveTo(file.toPath(), overwrite = true)
    }

    override fun close() {
        synchronized(lock) {
            engine?.stop(gracePeriodMillis = 0, timeoutMillis = 5_000)
            engine = null
            boundBaseUrl = null
        }
        client.close()
    }

    private data class OriginResponse(
        val status: HttpStatusCode,
        val bytes: ByteArray,
    )

    private class LocalMavenProxyCounters {
        val artifactHits = AtomicLong()
        val alternateArtifactMisses = AtomicLong()
        val knownComponentFallthroughs = AtomicLong()
        val metadataOnlyArtifactFallbacks = AtomicLong()
        val gradlePomHits = AtomicLong()
        val originFallbacks = AtomicLong()
        val originFailures = AtomicLong()
        val requestFailures = AtomicLong()
        val checksumHits = AtomicLong()
        val writeThroughCacheHits = AtomicLong()
        val bytesServed = AtomicLong()

        fun snapshot(): LocalMavenResolutionStats = LocalMavenResolutionStats(
            artifactHits = artifactHits.get(),
            alternateArtifactMisses = alternateArtifactMisses.get(),
            knownComponentFallthroughs = knownComponentFallthroughs.get(),
            metadataOnlyArtifactFallbacks = metadataOnlyArtifactFallbacks.get(),
            gradlePomHits = gradlePomHits.get(),
            originFallbacks = originFallbacks.get(),
            originFailures = originFailures.get(),
            requestFailures = requestFailures.get(),
            checksumHits = checksumHits.get(),
            writeThroughCacheHits = writeThroughCacheHits.get(),
            bytesServed = bytesServed.get(),
        )
    }

    companion object {
        private val logger = org.gradle.api.logging.Logging.getLogger(LocalMavenProxyServer::class.java)
    }
}

private sealed interface ServedResponse {
    val status: HttpStatusCode

    data class Bytes(
        override val status: HttpStatusCode,
        val bytes: ByteArray,
    ) : ServedResponse

    data class File(
        override val status: HttpStatusCode,
        val file: java.io.File,
    ) : ServedResponse
}

private fun isPomPath(path: String): Boolean = path.endsWith(".pom")

private fun isMavenMetadataPath(path: String): Boolean = path.endsWith("/maven-metadata.xml")

private fun isChecksumPath(path: String): Boolean =
    path.endsWith(".sha1") || path.endsWith(".md5") || path.endsWith(".sha256")

private fun checksumBasePath(checksumPath: String): String =
    checksumPath.substringBeforeLast(".")

private fun gavFromMavenPath(path: String): String {
    return gavFromMavenPathOrNull(path) ?: error("Cannot derive GAV from Maven path $path")
}

private fun gavFromMavenPathOrNull(path: String): String? {
    return MavenPath.parse(path)?.coordinates?.gav
}

private fun concreteGavFromMavenPathOrNull(path: String): String? {
    if (!isConcreteMavenArtifactPath(path)) return null
    return gavFromMavenPathOrNull(path)
}

private fun artifactExtension(path: String): String =
    path.substringAfterLast('.', missingDelimiterValue = "")

/**
 * Recognizes only the canonical "main" artifact filename for a GAV (`<module>-<version>.<ext>`,
 * no classifier) and returns its extension; classified artifacts return null. Feeding these
 * pairs into [knownMainArtifactExtensionsByGav] tells [isKnownAlternateArtifactProbe] which
 * extensions are known to exist for a GAV so probes for anything else can be rejected without
 * an origin round-trip.
 */
private fun mainArtifactExtensionByGav(path: String): Pair<String, String>? {
    val mavenPath = MavenPath.parse(path) ?: return null
    val extension = artifactExtension(path).takeIf(String::isNotBlank) ?: return null
    val expectedFileName = "${mavenPath.coordinates.module}-${mavenPath.coordinates.version}.$extension"
    return if (mavenPath.fileName == expectedFileName) {
        mavenPath.coordinates.gav to extension
    } else {
        null
    }
}

private fun digestResponse(algorithmSuffix: String, response: ServedResponse): String {
    val digest = messageDigest(algorithmSuffix)
    when (response) {
        is ServedResponse.Bytes -> digest.update(response.bytes)
        is ServedResponse.File -> response.file.inputStream().use { input ->
            updateDigestFromInput(digest, input)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun updateDigestFromInput(
    digest: MessageDigest,
    input: InputStream,
) {
    val buffer = ByteArray(DEFAULT_DIGEST_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return
        digest.update(buffer, 0, read)
    }
}

private fun messageDigest(algorithmSuffix: String): MessageDigest {
    val algorithm = when (algorithmSuffix) {
        "sha1" -> "SHA-1"
        "md5" -> "MD5"
        "sha256" -> "SHA-256"
        else -> error("Unsupported checksum suffix $algorithmSuffix")
    }
    return MessageDigest.getInstance(algorithm)
}

private const val DEFAULT_DIGEST_BUFFER_SIZE = 16 * 1024
