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
import com.sun.net.httpserver.HttpServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalMavenProxyServerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `serves artifacts and checksums from the Gradle artifact index`() {
        val artifact = temporaryFolder.newFile("library-1.0.jar")
        artifact.writeText("jar-bytes")
        newProxy(
            artifactIndex = mapOf("com/example/library/1.0/library-1.0.jar" to artifact)
        ).use { proxy ->
            val artifactResponse = get("${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.jar")
            val checksumResponse = get("${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.jar.sha1")

            assertEquals(200, artifactResponse.code)
            assertEquals("jar-bytes", artifactResponse.body)
            assertEquals(200, checksumResponse.code)
            assertEquals(sha1("jar-bytes".toByteArray()), checksumResponse.body)
            assertEquals(1, proxy.stats().artifactHits)
            assertEquals(1, proxy.stats().checksumHits)
        }
    }

    @Test
    fun `serves known component poms through the lazy resolver`() {
        val pom = temporaryFolder.newFile("library-1.0.pom")
        pom.writeText("<project/>")
        val queries = AtomicInteger()
        val resolver = object : PomFileResolver {
            override fun resolvePom(gav: String): File? {
                queries.incrementAndGet()
                assertEquals("com.example:library:1.0", gav)
                return pom
            }
        }

        newProxy(pomFileResolver = resolver).use { proxy ->
            repeat(2) {
                val response = get("${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.pom")
                assertEquals(200, response.code)
                assertEquals("<project/>", response.body)
            }

            assertEquals(2, queries.get())
            assertEquals(2, proxy.stats().gradlePomHits)
        }
    }

    @Test
    fun `falls back unknown poms to origin with auth and caches the response`() {
        FixtureOriginServer(
            responses = mapOf("com/example/parent/1.0/parent-1.0.pom" to "<parent/>"),
            expectedHeader = "Private-Token" to "secret"
        ).use { origin ->
            newProxy(
                repositories = listOf(
                    LocalMavenProxyOrigin(
                        name = "origin",
                        url = origin.baseUrl,
                        auth = LocalMavenProxyAuth.Header("Private-Token", "secret")
                    )
                )
            ).use { proxy ->
                val url = "${proxy.baseUrl()}/r/0/com/example/parent/1.0/parent-1.0.pom"

                assertEquals("<parent/>", get(url).body)
                assertEquals("<parent/>", get(url).body)

                assertEquals(1, origin.requests.get())
                assertEquals(1, proxy.stats().originFallbacks)
                assertEquals(1, proxy.stats().writeThroughCacheHits)
            }
        }
    }

    @Test
    fun `replays basic auth during origin fallback`() {
        val expectedAuth = "Basic " + Base64.getEncoder().encodeToString("user:pass".toByteArray())
        FixtureOriginServer(
            responses = mapOf("com/example/parent/1.0/parent-1.0.pom" to "<parent/>"),
            expectedHeader = "Authorization" to expectedAuth
        ).use { origin ->
            newProxy(
                repositories = listOf(
                    LocalMavenProxyOrigin(
                        name = "origin",
                        url = origin.baseUrl,
                        auth = LocalMavenProxyAuth.Basic("user", "pass")
                    )
                )
            ).use { proxy ->
                val response = get("${proxy.baseUrl()}/r/0/com/example/parent/1.0/parent-1.0.pom")

                assertEquals(200, response.code)
                assertEquals("<parent/>", response.body)
                assertEquals(1, origin.requests.get())
            }
        }
    }

    @Test
    fun `concurrent origin misses for the same path write through once`() {
        FixtureOriginServer(
            responses = mapOf("com/example/parent/1.0/parent-1.0.pom" to "<parent/>")
        ).use { origin ->
            newProxy(
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val executor = Executors.newFixedThreadPool(8)
                try {
                    val url = "${proxy.baseUrl()}/r/0/com/example/parent/1.0/parent-1.0.pom"
                    val responses = (1..8)
                        .map { executor.submit<HttpResponse> { get(url) } }
                        .map { future -> future.get() }

                    assertTrue(responses.all { response -> response.code == 200 })
                    assertTrue(responses.all { response -> response.body == "<parent/>" })
                    assertEquals(1, origin.requests.get())
                    assertEquals(1, proxy.stats().originFallbacks)
                    assertEquals(7, proxy.stats().writeThroughCacheHits)
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `fails closed when a resolved artifact is missing from the Gradle artifact index`() {
        FixtureOriginServer(
            responses = mapOf("com/example/library/1.0/library-1.0.jar" to "origin-jar")
        ).use { origin ->
            newProxy(
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val response = get("${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.jar")

                assertEquals(500, response.code)
                assertEquals(0, origin.requests.get())
                assertEquals(1, proxy.stats().hardArtifactMisses)
            }
        }
    }

    @Test
    fun `fails closed when a known component pom cannot be read`() {
        val resolver = object : PomFileResolver {
            override fun resolvePom(gav: String): File? = error("known component missing pom")
        }

        newProxy(pomFileResolver = resolver).use { proxy ->
            val response = get("${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.pom")

            assertEquals(500, response.code)
            assertEquals(1, proxy.stats().knownPomFailures)
        }
    }

    private fun newProxy(
        artifactIndex: Map<String, File> = emptyMap(),
        pomFileResolver: PomFileResolver = object : PomFileResolver {
            override fun resolvePom(gav: String): File? = null
        },
        repositories: List<LocalMavenProxyOrigin> = listOf(proxyOrigin(url = "https://repo.example.com"))
    ): LocalMavenProxyServer {
        return LocalMavenProxyServer(
            cacheDir = temporaryFolder.newFolder("proxy-cache"),
            origins = repositories
        ).apply {
            configure(
                artifactIndex = artifactIndex,
                pomFileResolver = pomFileResolver
            )
        }
    }

    private fun proxyOrigin(
        url: String,
        auth: LocalMavenProxyAuth = LocalMavenProxyAuth.None
    ): LocalMavenProxyOrigin = LocalMavenProxyOrigin(
        name = "origin",
        url = url,
        auth = auth
    )

    private fun get(url: String): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        val body = if (connection.responseCode < 400) {
            connection.inputStream.bufferedReader().readText()
        } else {
            connection.errorStream?.bufferedReader()?.readText().orEmpty()
        }
        return HttpResponse(connection.responseCode, body)
    }

    private fun sha1(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-1")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class HttpResponse(
        val code: Int,
        val body: String
    )

    private class FixtureOriginServer(
        responses: Map<String, String>,
        private val expectedHeader: Pair<String, String>? = null,
    ) : AutoCloseable {
        val requests = AtomicInteger()
        private val executor = Executors.newSingleThreadExecutor()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val baseUrl: String

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                requests.incrementAndGet()
                val headerMatches = expectedHeader?.let { (name, value) ->
                    exchange.requestHeaders.getFirst(name) == value
                } ?: true
                val path = exchange.requestURI.rawPath.removePrefix("/")
                val response = responses[path]
                if (!headerMatches || response == null) {
                    exchange.sendResponseHeaders(404, 0)
                    exchange.responseBody.close()
                    return@createContext
                }
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { output -> output.write(bytes) }
            }
            server.start()
            baseUrl = "http://127.0.0.1:${server.address.port}"
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
