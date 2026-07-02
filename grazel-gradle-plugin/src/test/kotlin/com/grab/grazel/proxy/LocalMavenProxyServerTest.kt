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
import com.grab.grazel.gradle.dependencies.PomFileResolution
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
    fun `serves known component poms from the Gradle pom resolver`() {
        val pom = temporaryFolder.newFile("library-1.0.pom")
        pom.writeText("<project/>")
        val artifact = temporaryFolder.newFile("library-1.0.jar")
        artifact.writeText("jar-bytes")

        newProxy(
            artifactIndex = mapOf("com/example/library/1.0/library-1.0.jar" to artifact),
            pomFilesByGav = mapOf("com.example:library:1.0" to pom)
        ).use { proxy ->
            repeat(2) {
                val response = get("${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.pom")
                assertEquals(200, response.code)
                assertEquals("<project/>", response.body)
            }

            assertEquals(2, proxy.stats().gradlePomHits)
        }
    }

    @Test
    fun `pom requests without artifact index entries use repository origin`() {
        val pom = temporaryFolder.newFile("library-1.0.pom")
        pom.writeText("<project/>")

        FixtureOriginServer(
            responses = mapOf("com/example/library/1.0/library-1.0.pom" to "<origin-project/>")
        ).use { origin ->
            newProxy(
                pomFilesByGav = mapOf("com.example:library:1.0" to pom),
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val response = get("${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.pom")

                assertEquals(200, response.code)
                assertEquals("<origin-project/>", response.body)
                assertEquals(1, origin.requests.get())
                assertEquals(0, proxy.stats().gradlePomHits)
                assertEquals(1, proxy.stats().originFallbacks)
            }
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
                        auth = RepositoryAuth.Header("Private-Token", "secret")
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
    fun `hard fails known component poms when the Gradle pom resolver misses`() {
        val artifact = temporaryFolder.newFile("library-1.0.jar")
        artifact.writeText("jar-bytes")
        FixtureOriginServer(
            responses = mapOf("com/example/library/1.0/library-1.0.pom" to "<project/>")
        ).use { origin ->
            newProxy(
                artifactIndex = mapOf("com/example/library/1.0/library-1.0.jar" to artifact),
                knownComponentGavs = setOf("com.example:library:1.0"),
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val response = get("${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.pom")

                assertEquals(500, response.code)
                assertTrue(response.body.contains("Missing Gradle-resolved POM"))
                assertEquals(0, origin.requests.get())
                assertEquals(0, proxy.stats().originFallbacks)
                assertEquals(1, proxy.stats().knownPomFailures)
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
                        auth = RepositoryAuth.Basic("user", "pass")
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
                assertEquals(0, proxy.stats().artifactMisses)
            }
        }
    }

    @Test
    fun `does not hard fail alternate artifact probes for extra indexed artifacts`() {
        val resolvedArtifact = temporaryFolder.newFile("library-1.0.aar")
        resolvedArtifact.writeText("gradle-aar")
        FixtureOriginServer(
            responses = mapOf("com/example/library/1.0/library-1.0.jar" to "origin-jar")
        ).use { origin ->
            newProxy(
                artifactIndex = mapOf("com/example/library/1.0/library-1.0.aar" to resolvedArtifact),
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val response = get("${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.jar")

                assertEquals(404, response.code)
                assertEquals(0, origin.requests.get())
                assertEquals(1, proxy.stats().alternateArtifactMisses)
                assertEquals(0, proxy.stats().artifactMisses)
            }
        }
    }

    @Test
    fun `does not hard fail alternate artifact probes for active lockfile aar artifacts`() {
        val allowedPath = "com/example/android-lib/2.0/android-lib-2.0.aar"
        FixtureOriginServer(
            responses = mapOf("com/example/android-lib/2.0/android-lib-2.0.jar" to "origin-jar")
        ).use { origin ->
            newProxy(
                allowedOriginArtifactPaths = setOf(allowedPath),
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val response = get("${proxy.baseUrl()}/r/0/com/example/android-lib/2.0/android-lib-2.0.jar")

                assertEquals(404, response.code)
                assertEquals(0, origin.requests.get())
                assertEquals(1, proxy.stats().alternateArtifactMisses)
                assertEquals(0, proxy.stats().artifactMisses)
            }
        }
    }

    @Test
    fun `classified jar artifacts do not make a missing main jar request valid`() {
        val sourcesArtifact = temporaryFolder.newFile("android-lib-2.0-sources.jar")
        sourcesArtifact.writeText("source-jar")
        val allowedPath = "com/example/android-lib/2.0/android-lib-2.0.aar"
        FixtureOriginServer(
            responses = mapOf("com/example/android-lib/2.0/android-lib-2.0.jar" to "origin-jar")
        ).use { origin ->
            newProxy(
                artifactIndex = mapOf("com/example/android-lib/2.0/android-lib-2.0-sources.jar" to sourcesArtifact),
                allowedOriginArtifactPaths = setOf(allowedPath),
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val response = get("${proxy.baseUrl()}/r/0/com/example/android-lib/2.0/android-lib-2.0.jar")

                assertEquals(404, response.code)
                assertEquals(0, origin.requests.get())
                assertEquals(1, proxy.stats().alternateArtifactMisses)
                assertEquals(0, proxy.stats().artifactMisses)
            }
        }
    }

    @Test
    fun `does not hard fail alternate artifact probes for active lockfile pom artifacts`() {
        val allowedPath = "com/example/platform/3.0/platform-3.0.pom"
        FixtureOriginServer(
            responses = mapOf("com/example/platform/3.0/platform-3.0.jar" to "origin-jar")
        ).use { origin ->
            newProxy(
                allowedOriginArtifactPaths = setOf(allowedPath),
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val response = get("${proxy.baseUrl()}/r/0/com/example/platform/3.0/platform-3.0.jar")

                assertEquals(404, response.code)
                assertEquals(0, origin.requests.get())
                assertEquals(1, proxy.stats().alternateArtifactMisses)
                assertEquals(0, proxy.stats().artifactMisses)
            }
        }
    }

    @Test
    fun `hard fails known concrete component artifact when exact same type is absent from Gradle`() {
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

                assertEquals(500, response.code)
                assertTrue(response.body.contains("Missing Gradle-resolved artifact"))
                assertEquals(0, origin.requests.get())
                assertEquals(0, proxy.stats().originFallbacks)
                assertEquals(0, proxy.stats().alternateArtifactMisses)
                assertEquals(1, proxy.stats().artifactMisses)
            }
        }
    }

    @Test
    fun `hard fails known concrete component artifacts when metadata-only fallback is not declared`() {
        FixtureOriginServer(
            responses = mapOf("com/example/library/1.0/library-1.0.aar" to "origin-aar")
        ).use { origin ->
            newProxy(
                knownComponentGavs = setOf("com.example:library:1.0"),
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val url = "${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.aar"

                val response = get(url)

                assertEquals(500, response.code)
                assertTrue(response.body.contains("Missing Gradle-resolved artifact"))
                assertEquals(0, origin.requests.get())
                assertEquals(0, proxy.stats().originFallbacks)
                assertEquals(0, proxy.stats().alternateArtifactMisses)
                assertEquals(1, proxy.stats().artifactMisses)
            }
        }
    }

    @Test
    fun `metadata-only concrete artifacts for unknown extra gavs fall back to origin and cache the response`() {
        FixtureOriginServer(
            responses = mapOf("com/example/library/1.0/library-1.0.aar" to "origin-aar")
        ).use { origin ->
            newProxy(
                metadataOnlyGavs = setOf("com.example:library:1.0"),
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val url = "${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.aar"

                assertEquals("origin-aar", get(url).body)
                assertEquals("origin-aar", get(url).body)

                assertEquals(1, origin.requests.get())
                assertEquals(1, proxy.stats().originFallbacks)
                assertEquals(1, proxy.stats().writeThroughCacheHits)
                assertEquals(2, proxy.stats().metadataOnlyArtifactFallbacks)
                assertEquals(0, proxy.stats().artifactMisses)
            }
        }
    }

    @Test
    fun `metadata-only known artifacts fall back to origin and cache the response`() {
        val path = "com/example/library/1.0/library-1.0.aar"
        FixtureOriginServer(
            responses = mapOf(path to "origin-aar")
        ).use { origin ->
            newProxy(
                knownComponentGavs = setOf("com.example:library:1.0"),
                metadataOnlyGavs = setOf("com.example:library:1.0"),
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val url = "${proxy.baseUrl()}/r/0/$path"

                assertEquals("origin-aar", get(url).body)
                assertEquals("origin-aar", get(url).body)

                assertEquals(1, origin.requests.get())
                assertEquals(1, proxy.stats().originFallbacks)
                assertEquals(1, proxy.stats().writeThroughCacheHits)
                assertEquals(2, proxy.stats().metadataOnlyArtifactFallbacks)
                assertEquals(0, proxy.stats().artifactMisses)
            }
        }
    }

    @Test
    fun `metadata-only artifact checksums are derived from origin bytes without counting artifact fallback twice`() {
        val path = "com/example/library/1.0/library-1.0.jar"
        FixtureOriginServer(
            responses = mapOf(path to "origin-jar")
        ).use { origin ->
            newProxy(
                knownComponentGavs = setOf("com.example:library:1.0"),
                metadataOnlyGavs = setOf("com.example:library:1.0"),
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val response = get("${proxy.baseUrl()}/r/0/$path.sha1")

                assertEquals(200, response.code)
                assertEquals(sha1("origin-jar".toByteArray()), response.body)
                assertEquals(1, origin.requests.get())
                assertEquals(1, proxy.stats().originFallbacks)
                assertEquals(0, proxy.stats().metadataOnlyArtifactFallbacks)
                assertEquals(1, proxy.stats().checksumHits)
            }
        }
    }

    @Test
    fun `unknown concrete artifacts fail closed while poms fall back to origin`() {
        val artifactPath = "com/example/override/2.0/override-2.0.jar"
        val pomPath = "com/example/override/2.0/override-2.0.pom"
        FixtureOriginServer(
            responses = mapOf(
                artifactPath to "origin-jar",
                pomPath to "origin-pom"
            )
        ).use { origin ->
            newProxy(
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val artifactResponse = get("${proxy.baseUrl()}/r/0/$artifactPath")
                val pomResponse = get("${proxy.baseUrl()}/r/0/$pomPath")

                assertEquals(500, artifactResponse.code)
                assertTrue(artifactResponse.body.contains("Missing Gradle-resolved artifact"))
                assertEquals(200, pomResponse.code)
                assertEquals("origin-pom", pomResponse.body)
                assertEquals(1, origin.requests.get())
                assertEquals(1, proxy.stats().artifactMisses)
                assertEquals(1, proxy.stats().originFallbacks)
            }
        }
    }

    @Test
    fun `exact lockfile artifacts fall back to origin and cache the response`() {
        val path = "com/example/lockfile-only/1.0/lockfile-only-1.0.jar"
        FixtureOriginServer(
            responses = mapOf(path to "origin-jar")
        ).use { origin ->
            newProxy(
                allowedOriginArtifactPaths = setOf(path),
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val url = "${proxy.baseUrl()}/r/0/$path"

                assertEquals("origin-jar", get(url).body)
                assertEquals("origin-jar", get(url).body)

                assertEquals(1, origin.requests.get())
                assertEquals(1, proxy.stats().originFallbacks)
                assertEquals(1, proxy.stats().writeThroughCacheHits)
                assertEquals(2, proxy.stats().lockfileArtifactFallbacks)
                assertEquals(0, proxy.stats().artifactMisses)
            }
        }
    }

    @Test
    fun `origin write-through cache is scoped by repository`() {
        val path = "com/example/lockfile-only/1.0/lockfile-only-1.0.jar"
        FixtureOriginServer(responses = emptyMap()).use { firstOrigin ->
            FixtureOriginServer(responses = mapOf(path to "origin-jar")).use { secondOrigin ->
                newProxy(
                    allowedOriginArtifactPaths = setOf(path),
                    repositories = listOf(
                        proxyOrigin(url = firstOrigin.baseUrl),
                        proxyOrigin(url = secondOrigin.baseUrl)
                    )
                ).use { proxy ->
                    val secondRepositoryResponse = get("${proxy.baseUrl()}/r/1/$path")
                    val firstRepositoryResponse = get("${proxy.baseUrl()}/r/0/$path")

                    assertEquals(200, secondRepositoryResponse.code)
                    assertEquals("origin-jar", secondRepositoryResponse.body)
                    assertEquals(404, firstRepositoryResponse.code)
                    assertEquals(1, firstOrigin.requests.get())
                    assertEquals(1, secondOrigin.requests.get())
                    assertEquals(1, proxy.stats().originFallbacks)
                    assertEquals(1, proxy.stats().originFailures)
                }
            }
        }
    }

    @Test
    fun `exact lockfile artifacts let coursier try the next repository after an origin miss`() {
        val path = "org/jetbrains/kotlin/kotlin-parcelize-runtime/1.9.24/kotlin-parcelize-runtime-1.9.24.jar"
        FixtureOriginServer(responses = emptyMap()).use { firstOrigin ->
            FixtureOriginServer(responses = mapOf(path to "parcelize-jar")).use { secondOrigin ->
                newProxy(
                    allowedOriginArtifactPaths = setOf(path),
                    repositories = listOf(
                        proxyOrigin(url = firstOrigin.baseUrl),
                        proxyOrigin(url = secondOrigin.baseUrl)
                    )
                ).use { proxy ->
                    val firstRepositoryResponse = get("${proxy.baseUrl()}/r/0/$path")
                    val secondRepositoryResponse = get("${proxy.baseUrl()}/r/1/$path")

                    assertEquals(404, firstRepositoryResponse.code)
                    assertEquals(200, secondRepositoryResponse.code)
                    assertEquals("parcelize-jar", secondRepositoryResponse.body)
                    assertEquals(1, firstOrigin.requests.get())
                    assertEquals(1, secondOrigin.requests.get())
                    assertEquals(1, proxy.stats().originFailures)
                    assertEquals(1, proxy.stats().originFallbacks)
                    assertEquals(1, proxy.stats().lockfileArtifactFallbacks)
                    assertEquals(0, proxy.stats().artifactMisses)
                }
            }
        }
    }

    @Test
    fun `gradle pom resolver does not mask repository misses for origin-bound artifacts`() {
        val gav = "com.example:lockfile-only:1.0"
        val artifactPath = "com/example/lockfile-only/1.0/lockfile-only-1.0.jar"
        val pomPath = "com/example/lockfile-only/1.0/lockfile-only-1.0.pom"
        val gradlePom = temporaryFolder.newFile("lockfile-only-1.0.pom")
        gradlePom.writeText("<gradle-pom/>")
        FixtureOriginServer(responses = emptyMap()).use { firstOrigin ->
            FixtureOriginServer(responses = mapOf(pomPath to "<origin-pom/>")).use { secondOrigin ->
                newProxy(
                    allowedOriginArtifactPaths = setOf(artifactPath),
                    pomFilesByGav = mapOf(gav to gradlePom),
                    repositories = listOf(
                        proxyOrigin(url = firstOrigin.baseUrl),
                        proxyOrigin(url = secondOrigin.baseUrl)
                    )
                ).use { proxy ->
                    val firstRepositoryPom = get("${proxy.baseUrl()}/r/0/$pomPath")
                    val secondRepositoryPom = get("${proxy.baseUrl()}/r/1/$pomPath")

                    assertEquals(404, firstRepositoryPom.code)
                    assertEquals(200, secondRepositoryPom.code)
                    assertEquals("<origin-pom/>", secondRepositoryPom.body)
                    assertEquals(1, firstOrigin.requests.get())
                    assertEquals(1, secondOrigin.requests.get())
                    assertEquals(0, proxy.stats().gradlePomHits)
                    assertEquals(1, proxy.stats().originFallbacks)
                    assertEquals(1, proxy.stats().originFailures)
                }
            }
        }
    }

    @Test
    fun `exact lockfile artifacts return origin misses for the requested repository`() {
        val path = "com/example/lockfile-only/1.0/lockfile-only-1.0.jar"
        FixtureOriginServer(responses = emptyMap()).use { firstOrigin ->
            FixtureOriginServer(responses = emptyMap()).use { secondOrigin ->
                newProxy(
                    allowedOriginArtifactPaths = setOf(path),
                    repositories = listOf(
                        proxyOrigin(url = firstOrigin.baseUrl),
                        proxyOrigin(url = secondOrigin.baseUrl)
                    )
                ).use { proxy ->
                    val response = get("${proxy.baseUrl()}/r/0/$path")

                    assertEquals(404, response.code)
                    assertEquals(1, firstOrigin.requests.get())
                    assertEquals(0, secondOrigin.requests.get())
                    assertEquals(1, proxy.stats().originFailures)
                    assertEquals(0, proxy.stats().originFallbacks)
                    assertEquals(0, proxy.stats().lockfileArtifactFallbacks)
                    assertEquals(0, proxy.stats().artifactMisses)
                }
            }
        }
    }

    @Test
    fun `hard fails unindexed Gradle cache artifacts instead of resolving arbitrary cache paths`() {
        FixtureOriginServer(
            responses = mapOf("com/example/library/1.0/library-1.0.jar" to "origin-jar")
        ).use { origin ->
            newProxy(
                artifactIndex = emptyMap(),
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val response = get("${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.jar")

                assertEquals(500, response.code)
                assertTrue(response.body.contains("Missing Gradle-resolved artifact"))
                assertEquals(0, origin.requests.get())
                assertEquals(0, proxy.stats().artifactHits)
                assertEquals(1, proxy.stats().artifactMisses)
            }
        }
    }

    @Test
    fun `hard fails unknown closure artifacts instead of falling back to origin`() {
        FixtureOriginServer(
            responses = mapOf("com/example/library/1.0/library-1.0.jar" to "origin-jar")
        ).use { origin ->
            newProxy(
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val url = "${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.jar"
                val response = get(url)

                assertEquals(500, response.code)
                assertTrue(response.body.contains("Missing Gradle-resolved artifact"))
                assertEquals(0, origin.requests.get())
                assertEquals(0, proxy.stats().originFallbacks)
                assertEquals(0, proxy.stats().writeThroughCacheHits)
                assertEquals(1, proxy.stats().artifactMisses)
            }
        }
    }

    @Test
    fun `fails closed when an indexed known component pom cannot be read`() {
        val missingPom = temporaryFolder.root.resolve("missing-library-1.0.pom")
        val artifact = temporaryFolder.newFile("library-1.0.jar")
        artifact.writeText("jar-bytes")

        newProxy(
            artifactIndex = mapOf("com/example/library/1.0/library-1.0.jar" to artifact),
            knownComponentGavs = setOf("com.example:library:1.0"),
            pomFilesByGav = mapOf("com.example:library:1.0" to missingPom)
        ).use { proxy ->
            val response = get("${proxy.baseUrl()}/r/0/com/example/library/1.0/library-1.0.pom")

            assertEquals(500, response.code)
            assertEquals(1, proxy.stats().knownPomFailures)
        }
    }

    @Test
    fun `origin request exceptions are counted separately from known pom failures`() {
        newProxy(
            repositories = listOf(proxyOrigin(url = "http://127.0.0.1:1"))
        ).use { proxy ->
            val response = get("${proxy.baseUrl()}/r/0/com/example/parent/1.0/parent-1.0.pom")

            assertEquals(500, response.code)
            assertEquals(0, proxy.stats().knownPomFailures)
            assertEquals(1, proxy.stats().requestFailures)
        }
    }

    @Test
    fun `active lockfile fallback cannot mask missing known Gradle artifacts`() {
        val path = "com/example/library/1.0/library-1.0.jar"
        FixtureOriginServer(
            responses = mapOf(path to "origin-jar")
        ).use { origin ->
            newProxy(
                knownComponentGavs = setOf("com.example:library:1.0"),
                allowedOriginArtifactPaths = setOf(path),
                repositories = listOf(proxyOrigin(url = origin.baseUrl))
            ).use { proxy ->
                val response = get("${proxy.baseUrl()}/r/0/$path")

                assertEquals(500, response.code)
                assertTrue(response.body.contains("Missing Gradle-resolved artifact"))
                assertEquals(0, origin.requests.get())
                assertEquals(0, proxy.stats().lockfileArtifactFallbacks)
                assertEquals(1, proxy.stats().artifactMisses)
            }
        }
    }

    private fun newProxy(
        artifactIndex: Map<String, File> = emptyMap(),
        knownComponentGavs: Set<String> = emptySet(),
        metadataOnlyGavs: Set<String> = emptySet(),
        allowedOriginArtifactPaths: Set<String> = emptySet(),
        pomFilesByGav: Map<String, File> = emptyMap(),
        repositories: List<LocalMavenProxyOrigin> = listOf(proxyOrigin(url = "https://repo.example.com"))
    ): LocalMavenProxyServer {
        return LocalMavenProxyServer(
            cacheDir = temporaryFolder.newFolder("proxy-cache"),
            origins = repositories
        ).apply {
            configure(
                artifactIndex = artifactIndex,
                knownComponentGavs = knownComponentGavs,
                metadataOnlyGavs = metadataOnlyGavs,
                allowedOriginArtifactPaths = allowedOriginArtifactPaths,
                pomFileResolver = PomFileResolver { gav ->
                    pomFilesByGav[gav]?.let { pomFile ->
                        if (pomFile.exists()) {
                            PomFileResolution.Found(pomFile)
                        } else {
                            PomFileResolution.Unavailable(
                                gav = gav,
                                message = "Configured test POM does not exist for $gav"
                            )
                        }
                    } ?: PomFileResolution.Unknown
                }
            )
        }
    }

    private fun proxyOrigin(
        url: String,
        auth: RepositoryAuth = RepositoryAuth.None
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
