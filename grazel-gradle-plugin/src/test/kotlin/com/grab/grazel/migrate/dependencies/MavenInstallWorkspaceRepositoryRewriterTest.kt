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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MavenInstallWorkspaceRepositoryRewriterTest {

    @Test
    fun `rewrite only replaces urls inside repository blocks`() {
        val rewritten = MavenInstallWorkspaceRepositoryRewriter.rewrite(
            workspace = WORKSPACE_WITH_REPOSITORIES,
            urlReplacements = mapOf(
                "https://dl.google.com/dl/android/maven2/" to "http://127.0.0.1:3456/r/0/",
                "https://repo.maven.apache.org/maven2/" to "http://127.0.0.1:3456/r/1/"
            )
        )

        assertThat(rewritten).isEqualTo(LOCALHOST_WORKSPACE)
    }

    @Test
    fun `restore reverses a previous rewrite`() {
        val restored = MavenInstallWorkspaceRepositoryRewriter.rewrite(
            workspace = LOCALHOST_WORKSPACE,
            urlReplacements = mapOf(
                "http://127.0.0.1:3456/r/0/" to "https://dl.google.com/dl/android/maven2/",
                "http://127.0.0.1:3456/r/1/" to "https://repo.maven.apache.org/maven2/"
            )
        )

        assertThat(restored).isEqualTo(WORKSPACE_WITH_REPOSITORIES)
    }
}

private val WORKSPACE_WITH_REPOSITORIES = """
    http_archive(
        name = "rules_jvm_external",
        url = "https://repo.maven.apache.org/maven2/not-a-repository-entry.zip",
    )

    maven_install(
        name = "maven",
        repositories = [
            "https://dl.google.com/dl/android/maven2/",
            "https://repo.maven.apache.org/maven2/",
        ] + DAGGER_REPOSITORIES,
    )
""".trimIndent()

private val LOCALHOST_WORKSPACE = """
    http_archive(
        name = "rules_jvm_external",
        url = "https://repo.maven.apache.org/maven2/not-a-repository-entry.zip",
    )

    maven_install(
        name = "maven",
        repositories = [
            "http://127.0.0.1:3456/r/0/",
            "http://127.0.0.1:3456/r/1/",
        ] + DAGGER_REPOSITORIES,
    )
""".trimIndent()
