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

package com.grab.grazel.gradle

import com.grab.grazel.buildProject
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.credentials.HttpHeaderCredentials
import org.gradle.kotlin.dsl.credentials
import org.gradle.kotlin.dsl.repositories
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepositoryAuthTest {

    @Test
    fun `repository data source captures none basic and header auth without changing support`() {
        val rootProject = buildProject("root")
        rootProject.repositories {
            maven {
                name = "public"
                url = rootProject.uri("https://public.example.com/maven")
            }
            maven {
                name = "basic"
                url = rootProject.uri("https://basic.example.com/maven")
                credentials(PasswordCredentials::class) {
                    username = "user"
                    password = "pass"
                }
            }
            maven {
                name = "header"
                url = rootProject.uri("https://header.example.com/maven")
                credentials(HttpHeaderCredentials::class) {
                    name = "Private-Token"
                    value = "secret"
                }
            }
        }

        val dataSource = DefaultRepositoryDataSource(rootProject)
        val legacyRepositories = dataSource.allRepositoriesLazy.get().associateBy { repository -> repository.name }
        val repositoriesWithAuth = dataSource.allRepositoriesWithAuthLazy.get().associateBy { repository -> repository.name }

        assertEquals(RepositoryAuth.None, repositoriesWithAuth.getValue("public").auth)
        assertEquals(
            RepositoryAuth.Basic(username = "user", password = "pass"),
            repositoriesWithAuth.getValue("basic").auth
        )
        assertEquals(
            RepositoryAuth.Header(name = "Private-Token", value = "secret"),
            repositoriesWithAuth.getValue("header").auth
        )
        assertNull(legacyRepositories.getValue("header").username)
        assertNull(legacyRepositories.getValue("header").password)
        assertEquals(
            listOf("public", "basic"),
            dataSource.supportedRepositories.map { repository -> repository.name }
        )
        assertEquals(listOf("header"), dataSource.unsupportedRepositoryNames)
    }

    @Test
    fun `none auth survives serialization boundary as singleton`() {
        val repository = RepositoryWithAuth(
            name = "public",
            url = "https://public.example.com/maven",
            auth = RepositoryAuth.None
        )

        val restored = roundTrip(repository)

        assertEquals(RepositoryAuth.None, restored.auth)
    }

    private fun roundTrip(repository: RepositoryWithAuth): RepositoryWithAuth {
        val bytes = ByteArrayOutputStream().use { byteStream ->
            ObjectOutputStream(byteStream).use { output ->
                output.writeObject(repository)
            }
            byteStream.toByteArray()
        }
        return ObjectInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.readObject() as RepositoryWithAuth
        }
    }
}
