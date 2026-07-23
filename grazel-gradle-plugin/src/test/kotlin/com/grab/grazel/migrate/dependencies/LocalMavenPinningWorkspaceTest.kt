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
import com.grab.grazel.bazel.rules.repositoryInputSpec
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMavenPinningWorkspaceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `proxy repositories can be restored to canonical repositories`() {
        val workspaceFile = temporaryFolder.newFile("WORKSPACE").apply {
            writeText(WORKSPACE_WITH_CANONICAL_REPOSITORIES)
        }
        val pinningWorkspace = LocalMavenPinningWorkspace(
            workspaceFile = workspaceFile,
            rootDirectory = temporaryFolder.root,
            repositoryRewrite = REPOSITORY_REWRITE
        )

        pinningWorkspace.withProxyRepositories {
            assertThat(workspaceFile.readText()).contains(""""http://127.0.0.1:12345/r/0/"""")
            assertThat(workspaceFile.readText()).contains(""""http://127.0.0.1:12345/r/1/"""")
        }

        assertThat(workspaceFile.readText()).isEqualTo(WORKSPACE_WITH_CANONICAL_REPOSITORIES)
    }

    @Test
    fun `proxy repositories replace credentialed repository aliases while active`() {
        val workspaceFile = temporaryFolder.newFile("WORKSPACE").apply {
            writeText(WORKSPACE_WITH_CREDENTIAL_REPOSITORY)
        }
        val pinningWorkspace = LocalMavenPinningWorkspace(
            workspaceFile = workspaceFile,
            rootDirectory = temporaryFolder.root,
            repositoryRewrite = CREDENTIAL_REPOSITORY_REWRITE
        )

        pinningWorkspace.withProxyRepositories {
            assertThat(workspaceFile.readText()).contains(""""http://127.0.0.1:12345/r/0/"""")
            assertThat(workspaceFile.readText()).doesNotContain("user:pass@repo.example")
        }

        assertThat(workspaceFile.readText()).isEqualTo(WORKSPACE_WITH_CREDENTIAL_REPOSITORY)
    }

    @Test
    fun `metadata only override targets are removed only while proxy repositories are active`() {
        val workspaceFile = temporaryFolder.newFile("WORKSPACE").apply {
            writeText(WORKSPACE_WITH_OVERRIDE_TARGETS)
        }
        val pinningWorkspace = LocalMavenPinningWorkspace(
            workspaceFile = workspaceFile,
            rootDirectory = temporaryFolder.root,
            repositoryRewrite = REPOSITORY_REWRITE,
            metadataOnlyShortIds = setOf("org.jetbrains.kotlinx:kotlinx-coroutines-core")
        )

        pinningWorkspace.withProxyRepositories {
            assertThat(workspaceFile.readText())
                .doesNotContain(""""org.jetbrains.kotlinx:kotlinx-coroutines-core":""")
            assertThat(workspaceFile.readText())
                .contains(""""org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm":""")
            assertThat(workspaceFile.readText()).contains(""""http://127.0.0.1:12345/r/0/"""")
        }

        assertThat(workspaceFile.readText()).isEqualTo(WORKSPACE_WITH_OVERRIDE_TARGETS)
    }

    @Test
    fun `metadata only override target removal ignores non override target lines`() {
        val workspaceFile = temporaryFolder.newFile("WORKSPACE").apply {
            writeText(WORKSPACE_WITH_COMMENTED_OVERRIDE_TARGETS)
        }
        val pinningWorkspace = LocalMavenPinningWorkspace(
            workspaceFile = workspaceFile,
            rootDirectory = temporaryFolder.root,
            repositoryRewrite = REPOSITORY_REWRITE,
            metadataOnlyShortIds = setOf("org.jetbrains.kotlinx:kotlinx-coroutines-core")
        )

        pinningWorkspace.withProxyRepositories {
            val workspace = workspaceFile.readText()
            assertThat(workspace).contains("# override target kept as a comment")
            assertThat(workspace).contains("not an override target")
            assertThat(workspace).contains("kept outside override_targets")
            assertThat(workspace)
                .doesNotContain("@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_core")
        }
    }

    @Test
    fun `active lockfiles are reconstructed while stale lockfiles are untouched`() {
        val workspaceFile = temporaryFolder.newFile("WORKSPACE").apply {
            writeText(WORKSPACE_WITH_CANONICAL_REPOSITORIES)
        }
        val mavenInstall = temporaryFolder.newFile(mavenInstallJsonName("maven")).apply {
            writeText(LOCALHOST_LOCKFILE)
        }
        val debugInstall = temporaryFolder.newFile(mavenInstallJsonName("debug_maven")).apply {
            writeText(LOCALHOST_LOCKFILE)
        }
        val staleInstall = temporaryFolder.newFile(mavenInstallJsonName("release_maven")).apply {
            writeText(LOCALHOST_LOCKFILE)
        }
        val pinningWorkspace = LocalMavenPinningWorkspace(
            workspaceFile = workspaceFile,
            rootDirectory = temporaryFolder.root,
            repositoryRewrite = REPOSITORY_REWRITE,
            repositoryInputs = MavenInstallRepositoryInputs(
                repositoriesByName = mapOf(
                    "maven" to listOf(repositoryInput("https://repo.example/maven2/")),
                    "debug_maven" to listOf(repositoryInput("https://repo.example/maven2/")),
                )
            )
        )

        pinningWorkspace.reconstructActiveLockfiles(activeMavenRepos = setOf("maven", "debug_maven"))

        assertThat(mavenInstall.readText()).contains(""""https://repo.example/maven2/"""")
        assertThat(debugInstall.readText()).contains(""""https://repo.example/maven2/"""")
        assertThat(staleInstall.readText()).isEqualTo(LOCALHOST_LOCKFILE)
    }
}

private val REPOSITORY_REWRITE = MavenInstallRepositoryRewrite(
    proxyToCanonicalUrl = mapOf(
        "http://127.0.0.1:12345/r/0/" to "https://repo.example/maven2/",
        "http://127.0.0.1:12345/r/1/" to "https://repo.maven.apache.org/maven2/"
    )
)

private val CREDENTIAL_REPOSITORY_REWRITE = MavenInstallRepositoryRewrite(
    proxyToCanonicalUrl = mapOf(
        "http://127.0.0.1:12345/r/0/" to "https://user:pass@repo.example/maven2/"
    ),
    canonicalToProxyUrl = mapOf(
        "https://repo.example/maven2/" to "http://127.0.0.1:12345/r/0/",
        "https://user:pass@repo.example/maven2/" to "http://127.0.0.1:12345/r/0/"
    )
)

private fun repositoryInput(url: String): MavenInstallRepositoryInput =
    MavenInstallRepositoryInput(
        repositoryInputSpec = repositoryInputSpec(url),
        canonicalUrl = url
    )

private val WORKSPACE_WITH_CANONICAL_REPOSITORIES = """
    maven_install(
        name = "maven",
        repositories = [
            "https://repo.example/maven2/",
            "https://repo.maven.apache.org/maven2/",
        ],
    )
""".trimIndent()

private val WORKSPACE_WITH_CREDENTIAL_REPOSITORY = """
    maven_install(
        name = "maven",
        repositories = [
            "https://user:pass@repo.example/maven2/",
        ],
    )
""".trimIndent()

private val WORKSPACE_WITH_OVERRIDE_TARGETS = """
    maven_install(
        name = "debug_maven",
        artifacts = [
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3",
        ],
        override_targets = {
            "org.jetbrains.kotlinx:kotlinx-coroutines-core": "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_core",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm": "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_core_jvm",
        },
        repositories = [
            "https://repo.example/maven2/",
        ],
    )
""".trimIndent()

private val WORKSPACE_WITH_COMMENTED_OVERRIDE_TARGETS = """
    maven_install(
        name = "debug_maven",
        # override target kept as a comment
        artifacts = [
            "not an override target",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3",
        ],
        generated_metadata = {
            "org.jetbrains.kotlinx:kotlinx-coroutines-core": "kept outside override_targets",
        },
        override_targets = {
            "org.jetbrains.kotlinx:kotlinx-coroutines-core": "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_core",
        },
        repositories = [
            "https://repo.example/maven2/",
        ],
    )
""".trimIndent()

private val LOCALHOST_LOCKFILE = """
    {
      "__AUTOGENERATED_FILE_DO_NOT_MODIFY_THIS_FILE_MANUALLY": "THERE_IS_NO_DATA_ONLY_ZUUL",
      "__INPUT_ARTIFACTS_HASH": {
        "com.example:lib": 1698394405,
        "repositories": -1
      },
      "__RESOLVED_ARTIFACTS_HASH": {
        "com.example:lib": -1
      },
      "artifacts": {
        "com.example:lib": {
          "shasums": {
            "jar": "abc123"
          },
          "version": "1.0"
        }
      },
      "dependencies": {
        "com.example:lib": []
      },
      "packages": {},
      "repositories": {
        "http://127.0.0.1:12345/r/0/": [
          "com.example:lib"
        ]
      },
      "services": {},
      "version": "3"
    }
""".trimIndent()
