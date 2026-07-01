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
import com.grab.grazel.util.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.File

class MavenInstallLockfileReconstructorTest {

    @Test
    fun `reconstruct rewrites localhost repositories and recomputes rje hashes`() {
        val reconstructed = reconstructor().reconstruct(
            lockfileContents = LOCALHOST_LOCKFILE,
            canonicalRepositoryInputs = CANONICAL_REPOSITORY_INPUTS
        )

        assertThat(reconstructed).isEqualTo(CANONICAL_LOCKFILE)
    }

    @Test
    fun `resolved hash uses rje cycle backup hash semantics`() {
        val reconstructed = reconstructor().reconstruct(
            lockfileContents = CYCLIC_LOCALHOST_LOCKFILE,
            canonicalRepositoryInputs = CANONICAL_REPOSITORY_INPUTS
        )

        assertThat(reconstructed).contains(""""com.example:a": 2068365301""")
        assertThat(reconstructed).contains(""""com.example:b": 457378379""")
    }

    @Test
    fun `resolved hash uses rje starlark repr for null shasums`() {
        val reconstructed = reconstructor().reconstruct(
            lockfileContents = NULL_SHASUM_LOCALHOST_LOCKFILE,
            canonicalRepositoryInputs = CANONICAL_REPOSITORY_INPUTS
        )

        assertThat(reconstructed).contains(""""com.example:null": 1886959677""")
    }

    @Test
    fun `resolved hash propagates null shasums as starlark none`() {
        val reconstructed = reconstructor().reconstruct(
            lockfileContents = NULL_SHASUM_DEPENDENT_LOCALHOST_LOCKFILE,
            canonicalRepositoryInputs = CANONICAL_REPOSITORY_INPUTS
        )

        assertThat(reconstructed).contains(""""com.example:null": 1886959677""")
        assertThat(reconstructed).contains(""""com.example:consumer": -279823740""")
    }

    @Test
    fun `reconstruct marks pom packaging artifacts skipped for rje aggregator generation`() {
        val reconstructed = reconstructor().reconstruct(
            lockfileContents = POM_PACKAGING_LOCALHOST_LOCKFILE,
            canonicalRepositoryInputs = CANONICAL_REPOSITORY_INPUTS
        )

        assertThat(reconstructed).contains("""  "skipped": [""")
        assertThat(reconstructed).contains("""    "com.example:already-skipped",""")
        assertThat(reconstructed).contains("""    "com.example:platform:pom"""")
    }

    @Test
    fun `reconstruct hashes supplied repository inputs instead of lockfile output repositories`() {
        val reconstructed = reconstructor().reconstruct(
            lockfileContents = LOCALHOST_LOCKFILE,
            canonicalRepositoryInputs = listOf(
                """{ "repo_url": "https://repo.example/maven2/" }""",
                """{ "repo_url": "https://unused.example/maven2/" }""",
            )
        )

        assertThat(reconstructed).contains(""""repositories": 1064750530""")
    }

    @Test
    fun `reconstruct keeps checked in rje lockfiles byte identical when urls do not change`() {
        val reconstructor = MavenInstallLockfileReconstructor(
            repositoryRewrite = MavenInstallRepositoryRewrite(proxyToCanonicalUrl = emptyMap())
        )

        CHECKED_IN_LOCKFILES.forEach { path ->
            val lockfile = File(path)
            if (lockfile.exists()) {
                val lockfileContents = lockfile.readText()
                assertThat(
                    reconstructor.reconstruct(
                        lockfileContents = lockfileContents,
                        canonicalRepositoryInputs = canonicalRepositoryInputsFromLockfileRepositories(lockfileContents)
                    )
                )
                    .isEqualTo(lockfile.readText())
            }
        }
    }
}

private fun reconstructor() =
    MavenInstallLockfileReconstructor(
        repositoryRewrite = MavenInstallRepositoryRewrite(
            proxyToCanonicalUrl = mapOf(
                "http://127.0.0.1:12345/r/0/" to "https://repo.example/maven2/"
            )
        )
    )

private val CANONICAL_REPOSITORY_INPUTS = listOf(
    """{ "repo_url": "https://repo.example/maven2/" }""",
)

private fun canonicalRepositoryInputsFromLockfileRepositories(lockfileContents: String): List<String> =
    Json.parseToJsonElement(lockfileContents)
        .jsonObject
        .getValue("repositories")
        .jsonObject
        .keys
        .map(::repositoryInputSpec)

private val CHECKED_IN_LOCKFILES = listOf(
    "android_test_maven_install.json",
    "debug_maven_install.json",
    "ksp_maven_install.json",
    "lint_maven_install.json",
    "maven_install.json",
    "test_maven_install.json",
)

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
      "packages": {
        "com.example:lib": [
          "com.example"
        ]
      },
      "repositories": {
        "http://127.0.0.1:12345/r/0/": [
          "com.example:lib"
        ]
      },
      "services": {},
      "version": "3"
    }
""".trimIndent()

private val CANONICAL_LOCKFILE = """
    {
      "__AUTOGENERATED_FILE_DO_NOT_MODIFY_THIS_FILE_MANUALLY": "THERE_IS_NO_DATA_ONLY_ZUUL",
      "__INPUT_ARTIFACTS_HASH": {
        "com.example:lib": 1698394405,
        "repositories": 1182908442
      },
      "__RESOLVED_ARTIFACTS_HASH": {
        "com.example:lib": 1888541845
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
      "packages": {
        "com.example:lib": [
          "com.example"
        ]
      },
      "repositories": {
        "https://repo.example/maven2/": [
          "com.example:lib"
        ]
      },
      "services": {},
      "version": "3"
    }

""".trimIndent()

private val CYCLIC_LOCALHOST_LOCKFILE = """
    {
      "__AUTOGENERATED_FILE_DO_NOT_MODIFY_THIS_FILE_MANUALLY": "THERE_IS_NO_DATA_ONLY_ZUUL",
      "__INPUT_ARTIFACTS_HASH": {
        "com.example:a": 1,
        "com.example:b": 2,
        "repositories": -1
      },
      "__RESOLVED_ARTIFACTS_HASH": {},
      "artifacts": {
        "com.example:a": {
          "shasums": {
            "jar": "aaa"
          },
          "version": "1.0"
        },
        "com.example:b": {
          "shasums": {
            "jar": "bbb"
          },
          "version": "1.0"
        }
      },
      "dependencies": {
        "com.example:a": [
          "com.example:b"
        ],
        "com.example:b": [
          "com.example:a"
        ]
      },
      "packages": {},
      "repositories": {
        "http://127.0.0.1:12345/r/0/": [
          "com.example:a",
          "com.example:b"
        ]
      },
      "services": {},
      "version": "3"
    }
""".trimIndent()

private val NULL_SHASUM_LOCALHOST_LOCKFILE = """
    {
      "__AUTOGENERATED_FILE_DO_NOT_MODIFY_THIS_FILE_MANUALLY": "THERE_IS_NO_DATA_ONLY_ZUUL",
      "__INPUT_ARTIFACTS_HASH": {
        "com.example:null": 1,
        "repositories": -1
      },
      "__RESOLVED_ARTIFACTS_HASH": {},
      "artifacts": {
        "com.example:null": {
          "shasums": {
            "jar": null
          },
          "version": "1.0"
        }
      },
      "dependencies": {
        "com.example:null": []
      },
      "packages": {},
      "repositories": {
        "http://127.0.0.1:12345/r/0/": [
          "com.example:null"
        ]
      },
      "services": {},
      "version": "3"
    }
""".trimIndent()

private val NULL_SHASUM_DEPENDENT_LOCALHOST_LOCKFILE = """
    {
      "__AUTOGENERATED_FILE_DO_NOT_MODIFY_THIS_FILE_MANUALLY": "THERE_IS_NO_DATA_ONLY_ZUUL",
      "__INPUT_ARTIFACTS_HASH": {
        "com.example:consumer": 1,
        "com.example:null": 2,
        "repositories": -1
      },
      "__RESOLVED_ARTIFACTS_HASH": {},
      "artifacts": {
        "com.example:consumer": {
          "shasums": {
            "jar": "ccc"
          },
          "version": "1.0"
        },
        "com.example:null": {
          "shasums": {
            "jar": null
          },
          "version": "1.0"
        }
      },
      "dependencies": {
        "com.example:consumer": [
          "com.example:null"
        ],
        "com.example:null": []
      },
      "packages": {},
      "repositories": {
        "http://127.0.0.1:12345/r/0/": [
          "com.example:consumer",
          "com.example:null"
        ]
      },
      "services": {},
      "version": "3"
    }
""".trimIndent()

private val POM_PACKAGING_LOCALHOST_LOCKFILE = """
    {
      "__AUTOGENERATED_FILE_DO_NOT_MODIFY_THIS_FILE_MANUALLY": "THERE_IS_NO_DATA_ONLY_ZUUL",
      "__INPUT_ARTIFACTS_HASH": {
        "com.example:jvm": 1,
        "com.example:platform": 2,
        "repositories": -1
      },
      "__RESOLVED_ARTIFACTS_HASH": {},
      "artifacts": {
        "com.example:jvm": {
          "shasums": {
            "jar": "aaa"
          },
          "version": "1.0"
        },
        "com.example:platform:pom": {
          "shasums": {
            "jar": "bbb"
          },
          "version": "1.0"
        }
      },
      "dependencies": {
        "com.example:jvm": [],
        "com.example:platform:pom": [
          "com.example:jvm"
        ]
      },
      "packages": {},
      "repositories": {
        "http://127.0.0.1:12345/r/0/": [
          "com.example:jvm",
          "com.example:platform:pom"
        ]
      },
      "services": {},
      "skipped": [
        "com.example:already-skipped"
      ],
      "version": "3"
    }
""".trimIndent()
