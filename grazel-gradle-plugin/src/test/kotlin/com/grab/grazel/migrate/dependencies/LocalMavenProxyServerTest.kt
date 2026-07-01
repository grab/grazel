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
import com.grab.grazel.gradle.dependencies.MavenArtifactFileResolver
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
    fun `does not route pom requests through the artifact resolver`() {
        val pom = temporaryFolder.newFile("library-1.0.pom")
        pom.writeText("<project/>")

        newProxy(
            artifactFileResolver = object : MavenArtifactFileResolver {
                override fun resolveArtifact(path: String): File? =
                    error("POM requests must not use the artifact resolver")
            },
            pomFileResolver = object : PomFileResolver {
                override fun resolvePom(gav: String): File? = pom
            }
        ).use { proxy ->
            val response = get("${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.pom")

            assertEquals(200, response.code)
            assertEquals("<project/>", response.body)
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
    fun `falls back known component poms to origin when the lazy resolver misses`() {
        FixtureOriginServer(
            responses = mapOf("com/example/library/1.0/library-1.0.pom" to "<project/>")
        ).use { origin ->
            newProxy(
                knownComponentGavs = setOf("com.example:library:1.0"),
                pomFileResolver = object : PomFileResolver {
                    override fun resolvePom(gav: String): File? = null
                },
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val response = get("${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.pom")

                assertEquals(200, response.code)
                assertEquals("<project/>", response.body)
                assertEquals(1, origin.requests.get())
                assertEquals(1, proxy.stats().originFallbacks)
                assertEquals(0, proxy.stats().knownPomFailures)
            }
        }
    }

    @Test
    fun `falls back maven metadata to origin and caches the response`() {
        FixtureOriginServer(
            responses = mapOf("com/example/library/maven-metadata.xml" to "<metadata/>")
        ).use { origin ->
            newProxy(
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val url = "${proxy.baseUrl()}/r/0/com/example/library/maven-metadata.xml"

                assertEquals("<metadata/>", get(url).body)
                assertEquals("<metadata/>", get(url).body)

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
    fun `does not fall back to origin for alternate artifact probes when Gradle has a concrete artifact`() {
        val resolvedArtifact = temporaryFolder.newFile("library-1.0.aar")
        resolvedArtifact.writeText("gradle-aar")
        FixtureOriginServer(
            responses = mapOf("com/example/library/1.0/library-1.0.jar" to "origin-jar")
        ).use { origin ->
            newProxy(
                artifactIndex = mapOf("com/example/library/1.0/library-1.0.aar" to resolvedArtifact),
                knownComponentGavs = setOf("com.example:library:1.0"),
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val response = get("${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.jar")

                assertEquals(404, response.code)
                assertEquals(0, origin.requests.get())
                assertEquals(1, proxy.stats().alternateArtifactMisses)
            }
        }
    }

    @Test
    fun `falls back known concrete component artifact to origin when exact same type is absent from Gradle`() {
        val resolvedClassifierArtifact = temporaryFolder.newFile("library-1.0-tests.jar")
        resolvedClassifierArtifact.writeText("gradle-tests-jar")
        FixtureOriginServer(
            responses = mapOf("com/example/library/1.0/library-1.0.jar" to "origin-jar")
        ).use { origin ->
            newProxy(
                artifactIndex = mapOf(
                    "com/example/library/1.0/library-1.0-tests.jar" to resolvedClassifierArtifact
                ),
                knownComponentGavs = setOf("com.example:library:1.0"),
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val response = get("${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.jar")

                assertEquals(200, response.code)
                assertEquals("origin-jar", response.body)
                assertEquals(1, origin.requests.get())
                assertEquals(1, proxy.stats().originFallbacks)
                assertEquals(0, proxy.stats().alternateArtifactMisses)
            }
        }
    }

    @Test
    fun `falls back known metadata-only component artifacts to origin`() {
        FixtureOriginServer(
            responses = mapOf("com/example/library/1.0/library-1.0.aar" to "origin-aar")
        ).use { origin ->
            newProxy(
                knownComponentGavs = setOf("com.example:library:1.0"),
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val url = "${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.aar"

                assertEquals("origin-aar", get(url).body)

                assertEquals(1, origin.requests.get())
                assertEquals(1, proxy.stats().originFallbacks)
                assertEquals(0, proxy.stats().alternateArtifactMisses)
            }
        }
    }

    @Test
    fun `serves unknown closure artifacts from the Gradle module cache before origin`() {
        val cachedArtifact = temporaryFolder.newFile("library-1.0.jar")
        cachedArtifact.writeText("cached-jar")
        FixtureOriginServer(
            responses = mapOf("com/example/library/1.0/library-1.0.jar" to "origin-jar")
        ).use { origin ->
            newProxy(
                artifactFileResolver = object : MavenArtifactFileResolver {
                    override fun resolveArtifact(path: String): File? {
                        assertEquals("com/example/library/1.0/library-1.0.jar", path)
                        return cachedArtifact
                    }
                },
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val response = get("${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.jar")

                assertEquals(200, response.code)
                assertEquals("cached-jar", response.body)
                assertEquals(0, origin.requests.get())
                assertEquals(1, proxy.stats().artifactHits)
            }
        }
    }

    @Test
    fun `falls back unknown closure artifacts to origin and caches the response`() {
        FixtureOriginServer(
            responses = mapOf("com/example/library/1.0/library-1.0.jar" to "origin-jar")
        ).use { origin ->
            newProxy(
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val url = "${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.jar"

                assertEquals("origin-jar", get(url).body)
                assertEquals("origin-jar", get(url).body)

                assertEquals(1, origin.requests.get())
                assertEquals(1, proxy.stats().originFallbacks)
                assertEquals(1, proxy.stats().writeThroughCacheHits)
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
        artifactFileResolver: MavenArtifactFileResolver = MavenArtifactFileResolver.None,
        knownComponentGavs: Set<String> = emptySet(),
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
                artifactFileResolver = artifactFileResolver,
                knownComponentGavs = knownComponentGavs,
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
