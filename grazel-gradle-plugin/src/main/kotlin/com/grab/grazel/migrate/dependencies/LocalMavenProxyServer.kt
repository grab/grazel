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

package com.grab.grazel.migrate.dependencies

import com.grab.grazel.gradle.dependencies.PomFileResolver
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

internal data class LocalMavenProxyStats(
    val artifactHits: Long = 0,
    val hardArtifactMisses: Long = 0,
    val gradlePomHits: Long = 0,
    val knownPomFailures: Long = 0,
    val originFallbacks: Long = 0,
    val originFailures: Long = 0,
    val checksumHits: Long = 0,
    val writeThroughCacheHits: Long = 0,
    val bytesServed: Long = 0,
)

internal data class LocalMavenProxyOrigin(
    val name: String,
    val url: String,
    val auth: LocalMavenProxyAuth = LocalMavenProxyAuth.None
)

internal sealed interface LocalMavenProxyAuth {
    object None : LocalMavenProxyAuth
    data class Basic(val username: String, val password: String) : LocalMavenProxyAuth
    data class Header(val name: String, val value: String) : LocalMavenProxyAuth
}

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
    private var pomFileResolver: PomFileResolver = object : PomFileResolver {
        override fun resolvePom(gav: String): File? = null
    }
    private var engine: ApplicationEngine? = null
    private var boundBaseUrl: String? = null

    fun configure(
        artifactIndex: Map<String, File>,
        pomFileResolver: PomFileResolver,
    ) {
        synchronized(lock) {
            this.artifactIndex = artifactIndex
            this.pomFileResolver = pomFileResolver
        }
    }

    fun baseUrl(): String {
        return synchronized(lock) {
            boundBaseUrl ?: startServerLocked()
        }
    }

    fun stats(): LocalMavenProxyStats = counters.snapshot()

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
                        counters.knownPomFailures.incrementAndGet()
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

    private suspend fun serve(
        repoIndex: Int?,
        path: String,
        countContentHit: Boolean = true,
    ): ServedResponse {
        if (repoIndex == null || path.isBlank()) {
            return ServedResponse.Bytes(HttpStatusCode.NotFound, ByteArray(0))
        }
        if (path.isChecksumPath()) {
            return serveChecksum(repoIndex, path)
        }
        artifactIndex[path]?.let { file ->
            return fileResponse(
                status = HttpStatusCode.OK,
                file = file,
                onServed = {
                    if (countContentHit) {
                        counters.artifactHits.incrementAndGet()
                    }
                }
            )
        }
        if (path.isPomPath()) {
            return servePom(repoIndex, path, countContentHit)
        }
        counters.hardArtifactMisses.incrementAndGet()
        return ServedResponse.Bytes(
            HttpStatusCode.InternalServerError,
            "Missing Gradle-resolved artifact $path".toByteArray()
        )
    }

    private suspend fun serveChecksum(repoIndex: Int, checksumPath: String): ServedResponse {
        val basePath = checksumPath.removeChecksumSuffix()
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

    private suspend fun servePom(
        repoIndex: Int,
        path: String,
        countContentHit: Boolean,
    ): ServedResponse {
        val gav = path.toGav()
        return try {
            pomFileResolver.resolvePom(gav)?.let { pom ->
                return fileResponse(
                    status = HttpStatusCode.OK,
                    file = pom,
                    onServed = {
                        if (countContentHit) {
                            counters.gradlePomHits.incrementAndGet()
                        }
                    }
                )
            }
            serveFromCacheOrOrigin(repoIndex, path)
        } catch (exception: Exception) {
            counters.knownPomFailures.incrementAndGet()
            ServedResponse.Bytes(
                HttpStatusCode.InternalServerError,
                exception.message.orEmpty().toByteArray()
            )
        }
    }

    private suspend fun serveFromCacheOrOrigin(repoIndex: Int, path: String): ServedResponse {
        val cachedFile = File(cacheDir, path)
        if (cachedFile.exists()) {
            return fileResponse(
                status = HttpStatusCode.OK,
                file = cachedFile,
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
                        onServed = { counters.writeThroughCacheHits.incrementAndGet() }
                    )
                }
                val originResponse = fetchOrigin(origin, path)
                if (cachedFile.exists()) {
                    return fileResponse(
                        status = HttpStatusCode.OK,
                        file = cachedFile,
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
                counters.bytesServed.addAndGet(bytes.size.toLong())
                ServedResponse.Bytes(HttpStatusCode.OK, bytes)
            }
        } finally {
            originMissMutexes.remove(originMissKey, originMissMutex)
        }
    }

    private suspend fun fetchOrigin(
        origin: LocalMavenProxyOrigin,
        path: String,
    ): OriginResponse {
        val response = client.get("${origin.url.trimEnd('/')}/$path") {
            when (val auth = origin.auth) {
                is LocalMavenProxyAuth.Basic -> basicAuth(auth.username, auth.password)
                is LocalMavenProxyAuth.Header -> header(auth.name, auth.value)
                LocalMavenProxyAuth.None -> Unit
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
        onServed: () -> Unit,
    ): ServedResponse {
        onServed()
        counters.bytesServed.addAndGet(file.length())
        return ServedResponse.File(status, file)
    }

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
        val hardArtifactMisses = AtomicLong()
        val gradlePomHits = AtomicLong()
        val knownPomFailures = AtomicLong()
        val originFallbacks = AtomicLong()
        val originFailures = AtomicLong()
        val checksumHits = AtomicLong()
        val writeThroughCacheHits = AtomicLong()
        val bytesServed = AtomicLong()

        fun snapshot(): LocalMavenProxyStats = LocalMavenProxyStats(
            artifactHits = artifactHits.get(),
            hardArtifactMisses = hardArtifactMisses.get(),
            gradlePomHits = gradlePomHits.get(),
            knownPomFailures = knownPomFailures.get(),
            originFallbacks = originFallbacks.get(),
            originFailures = originFailures.get(),
            checksumHits = checksumHits.get(),
            writeThroughCacheHits = writeThroughCacheHits.get(),
            bytesServed = bytesServed.get(),
        )
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

private fun String.isPomPath(): Boolean = endsWith(".pom")

private fun String.isChecksumPath(): Boolean =
    endsWith(".sha1") || endsWith(".md5") || endsWith(".sha256")

private fun String.removeChecksumSuffix(): String =
    substringBeforeLast(".")

private fun String.toGav(): String {
    val parts = split("/")
    require(parts.size >= 4) { "Cannot derive GAV from Maven path $this" }
    val version = parts[parts.lastIndex - 1]
    val artifact = parts[parts.lastIndex - 2]
    val group = parts.dropLast(3).joinToString(".")
    return "$group:$artifact:$version"
}

private fun digestResponse(algorithmSuffix: String, response: ServedResponse): String {
    val digest = messageDigest(algorithmSuffix)
    when (response) {
        is ServedResponse.Bytes -> digest.update(response.bytes)
        is ServedResponse.File -> response.file.inputStream().use { input ->
            digest.updateFrom(input)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun MessageDigest.updateFrom(input: InputStream) {
    val buffer = ByteArray(DEFAULT_DIGEST_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return
        update(buffer, 0, read)
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
