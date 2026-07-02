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

import com.google.common.truth.Truth.assertThat
import com.grab.grazel.gradle.RepositoryAuth
import com.grab.grazel.gradle.RepositoryWithAuth
import org.junit.Test

class LocalMavenProxyServiceTest {

    @Test
    fun `repository mappings restore the credentialed url when it is the generated repository input`() {
        val mappings = localMavenRepositoryProxyMappings(
            baseUrl = "http://127.0.0.1:12345",
            repositories = listOf(basicAuthRepository()),
            canonicalRepositoryUrls = setOf("https://user:pass@repo.example/maven2/")
        )

        assertThat(mappings.proxyToCanonicalUrl)
            .containsExactly("http://127.0.0.1:12345/r/0/", "https://user:pass@repo.example/maven2/")
        assertThat(mappings.canonicalToProxyUrl)
            .containsAtLeast(
                "https://repo.example/maven2/", "http://127.0.0.1:12345/r/0/",
                "https://user:pass@repo.example/maven2/", "http://127.0.0.1:12345/r/0/",
            )
    }

    @Test
    fun `repository mappings restore the credentialless url when it is the generated repository input`() {
        val mappings = localMavenRepositoryProxyMappings(
            baseUrl = "http://127.0.0.1:12345/",
            repositories = listOf(basicAuthRepository()),
            canonicalRepositoryUrls = setOf("https://repo.example/maven2/")
        )

        assertThat(mappings.proxyToCanonicalUrl)
            .containsExactly("http://127.0.0.1:12345/r/0/", "https://repo.example/maven2/")
        assertThat(mappings.canonicalToProxyUrl)
            .containsEntry("https://user:pass@repo.example/maven2/", "http://127.0.0.1:12345/r/0/")
    }

    @Test
    fun `repository mappings assign proxy indexes for external repository urls`() {
        val mappings = localMavenRepositoryProxyMappings(
            baseUrl = "http://127.0.0.1:12345/",
            repositories = listOf(basicAuthRepository()),
            canonicalRepositoryUrls = setOf(
                "https://repo.example/maven2/",
                "https://external.example/dagger/"
            )
        )

        assertThat(mappings.proxyToCanonicalUrl)
            .containsExactly(
                "http://127.0.0.1:12345/r/0/", "https://repo.example/maven2/",
                "http://127.0.0.1:12345/r/1/", "https://external.example/dagger/",
            )
        assertThat(mappings.canonicalToProxyUrl)
            .containsEntry("https://external.example/dagger/", "http://127.0.0.1:12345/r/1/")
    }

    @Test
    fun `proxy origins include external repository urls`() {
        val origins = localMavenProxyOrigins(
            repositories = listOf(basicAuthRepository()),
            canonicalRepositoryUrls = setOf(
                "https://repo.example/maven2/",
                "https://external.example/dagger/"
            )
        )

        assertThat(origins.map { origin -> origin.url })
            .containsExactly("https://repo.example/maven2/", "https://external.example/dagger/")
            .inOrder()
    }

    private fun basicAuthRepository(): RepositoryWithAuth =
        RepositoryWithAuth(
            name = "private",
            url = "https://repo.example/maven2/",
            auth = RepositoryAuth.Basic(username = "user", password = "pass")
        )
}
