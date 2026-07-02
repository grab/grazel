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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import kotlin.test.assertFailsWith

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
    fun `reconstruct fails local proxy first pin for pom packaging artifacts without baseline`() {
        val failure = assertFailsWith<IllegalStateException> {
            reconstructor().reconstruct(
                lockfileContents = POM_PACKAGING_LOCALHOST_LOCKFILE,
                canonicalRepositoryInputs = CANONICAL_REPOSITORY_INPUTS,
                baselineLockfileContents = null,
                requireBaselineForPomPackagingArtifacts = true
            )
        }

        assertThat(failure)
            .hasMessageThat()
            .contains("requires a baseline lockfile")
    }

    @Test
    fun `reconstruct marks new pom packaging artifacts skipped when baseline lockfile exists`() {
        val reconstructed = reconstructor().reconstruct(
            lockfileContents = POM_PACKAGING_LOCALHOST_LOCKFILE,
            canonicalRepositoryInputs = CANONICAL_REPOSITORY_INPUTS,
            baselineLockfileContents = CANONICAL_LOCKFILE
        )

        assertThat(reconstructed).contains("""  "skipped": [""")
        assertThat(reconstructed).contains("""    "com.example:already-skipped",""")
        assertThat(reconstructed).contains("""    "com.example:platform:pom"""")
    }

    @Test
    fun `reconstruct preserves baseline pom packaging skipped state`() {
        val reconstructed = reconstructor().reconstruct(
            lockfileContents = POM_PACKAGING_LOCALHOST_LOCKFILE,
            canonicalRepositoryInputs = CANONICAL_REPOSITORY_INPUTS,
            baselineLockfileContents = POM_PACKAGING_CANONICAL_LOCKFILE
        )
        val skipped = Json.parseToJsonElement(reconstructed)
            .jsonObject
            .getValue("skipped")
            .jsonArray
            .map { skipped -> skipped.jsonPrimitive.content }

        assertThat(skipped).containsExactly("com.example:already-skipped")
    }

    @Test
    fun `reconstruct ignores current skipped state for baseline pom packaging artifact`() {
        val reconstructed = reconstructor().reconstruct(
            lockfileContents = POM_PACKAGING_LOCALHOST_LOCKFILE_WITH_BASELINE_POM_SKIPPED,
            canonicalRepositoryInputs = CANONICAL_REPOSITORY_INPUTS,
            baselineLockfileContents = POM_PACKAGING_CANONICAL_LOCKFILE
        )
        val skipped = Json.parseToJsonElement(reconstructed)
            .jsonObject
            .getValue("skipped")
            .jsonArray
            .map { skipped -> skipped.jsonPrimitive.content }

        assertThat(skipped).containsExactly("com.example:already-skipped")
    }

    @Test
    fun `reconstruct fails when proxy shasums differ from baseline shasums`() {
        val failure = assertFailsWith<IllegalStateException> {
            reconstructor().reconstruct(
                lockfileContents = LOCALHOST_LOCKFILE.replace("abc123", "proxy-sha"),
                canonicalRepositoryInputs = CANONICAL_REPOSITORY_INPUTS,
                baselineLockfileContents = CANONICAL_LOCKFILE.replace("abc123", "baseline-sha")
            )
        }

        assertThat(failure).hasMessageThat().contains("changed shasums")
    }

    @Test
    fun `reconstruct accepts baseline shasums that match current shasums`() {
        val reconstructed = reconstructor().reconstruct(
            lockfileContents = LOCALHOST_LOCKFILE,
            canonicalRepositoryInputs = CANONICAL_REPOSITORY_INPUTS,
            baselineLockfileContents = CANONICAL_LOCKFILE
        )

        assertThat(reconstructed).contains(""""jar": "abc123"""")
    }

    @Test
    fun `reconstruct fails when current lockfile skips a baseline artifact`() {
        val failure = assertFailsWith<IllegalStateException> {
            reconstructor().reconstruct(
                lockfileContents = LOCALHOST_LOCKFILE_WITH_BASELINE_ARTIFACT_SKIPPED,
                canonicalRepositoryInputs = CANONICAL_REPOSITORY_INPUTS,
                baselineLockfileContents = CANONICAL_LOCKFILE
            )
        }

        assertThat(failure).hasMessageThat().contains("skipped artifacts that existed in the baseline")
    }

    @Test
    fun `reconstruct allows skipped baseline artifact when current artifact record still exists`() {
        val reconstructed = reconstructor().reconstruct(
            lockfileContents = LOCALHOST_LOCKFILE_WITH_SKIPPED_NULL_ARTIFACT,
            canonicalRepositoryInputs = CANONICAL_REPOSITORY_INPUTS,
            baselineLockfileContents = CANONICAL_LOCKFILE_WITH_SKIPPED_NULL_ARTIFACT
        )

        assertThat(reconstructed).contains(""""com.example:platform"""")
        assertThat(reconstructed).contains(""""jar": null""")
        assertThat(reconstructed).contains("""    "com.example:platform"""")
    }

    @Test
    fun `reconstruct hashes supplied repository inputs instead of lockfile output repositories`() {
        val reconstructed = reconstructor().reconstruct(
            lockfileContents = LOCALHOST_LOCKFILE,
            canonicalRepositoryInputs = listOf(
                repositoryInputSpec("https://repo.example/maven2/"),
                repositoryInputSpec("https://unused.example/maven2/"),
            )
        )

        assertThat(reconstructed).contains(""""repositories": 1064750530""")
    }

    @Test
    fun `reconstruct writes canonical lockfile repository urls with trailing slash`() {
        val reconstructed = MavenInstallLockfileReconstructor(
            repositoryRewrite = MavenInstallRepositoryRewrite(
                proxyToCanonicalUrl = mapOf(
                    "http://127.0.0.1:12345/r/0/" to "https://repo.example/maven2"
                )
            )
        ).reconstruct(
            lockfileContents = LOCALHOST_LOCKFILE,
            canonicalRepositoryInputs = listOf(
                repositoryInputSpec("https://repo.example/maven2"),
            )
        )

        assertThat(reconstructed).contains(""""https://repo.example/maven2/": [""")
        assertThat(reconstructed).doesNotContain("https://repo.example/maven2com")
    }

    @Test
    fun `reconstruct restores credentialed canonical repository urls when generated input uses credentials`() {
        val reconstructed = MavenInstallLockfileReconstructor(
            repositoryRewrite = MavenInstallRepositoryRewrite(
                proxyToCanonicalUrl = mapOf(
                    "http://127.0.0.1:12345/r/0/" to "https://user:pass@repo.example/maven2/"
                )
            )
        ).reconstruct(
            lockfileContents = LOCALHOST_LOCKFILE,
            canonicalRepositoryInputs = listOf(
                repositoryInputSpec("https://user:pass@repo.example/maven2/"),
            )
        )

        assertThat(reconstructed).contains(""""https://user:pass@repo.example/maven2/": [""")
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
    repositoryInputSpec("https://repo.example/maven2/"),
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

private val LOCALHOST_LOCKFILE_WITH_BASELINE_ARTIFACT_SKIPPED = """
    {
      "__AUTOGENERATED_FILE_DO_NOT_MODIFY_THIS_FILE_MANUALLY": "THERE_IS_NO_DATA_ONLY_ZUUL",
      "__INPUT_ARTIFACTS_HASH": {
        "com.example:lib": 1698394405,
        "repositories": -1
      },
      "__RESOLVED_ARTIFACTS_HASH": {
        "com.example:lib": -1
      },
      "artifacts": {},
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
      "skipped": [
        "com.example:lib"
      ],
      "version": "3"
    }
""".trimIndent()

private val LOCALHOST_LOCKFILE_WITH_SKIPPED_NULL_ARTIFACT = """
    {
      "__AUTOGENERATED_FILE_DO_NOT_MODIFY_THIS_FILE_MANUALLY": "THERE_IS_NO_DATA_ONLY_ZUUL",
      "__INPUT_ARTIFACTS_HASH": {
        "com.example:platform": 1,
        "com.example:platform:aar": 2,
        "repositories": -1
      },
      "__RESOLVED_ARTIFACTS_HASH": {},
      "artifacts": {
        "com.example:platform": {
          "shasums": {
            "jar": null
          },
          "version": "1.0"
        },
        "com.example:platform:aar": {
          "shasums": {
            "jar": "aar-sha"
          },
          "version": "1.0"
        }
      },
      "dependencies": {
        "com.example:platform": [],
        "com.example:platform:aar": []
      },
      "packages": {},
      "repositories": {
        "http://127.0.0.1:12345/r/0/": [
          "com.example:platform",
          "com.example:platform:aar"
        ]
      },
      "services": {},
      "skipped": [
        "com.example:platform"
      ],
      "version": "3"
    }
""".trimIndent()

private val CANONICAL_LOCKFILE_WITH_SKIPPED_NULL_ARTIFACT = """
    {
      "__AUTOGENERATED_FILE_DO_NOT_MODIFY_THIS_FILE_MANUALLY": "THERE_IS_NO_DATA_ONLY_ZUUL",
      "__INPUT_ARTIFACTS_HASH": {
        "com.example:platform": 1,
        "com.example:platform:aar": 2,
        "repositories": 1182908442
      },
      "__RESOLVED_ARTIFACTS_HASH": {},
      "artifacts": {
        "com.example:platform": {
          "shasums": {
            "jar": null
          },
          "version": "1.0"
        },
        "com.example:platform:aar": {
          "shasums": {
            "jar": "aar-sha"
          },
          "version": "1.0"
        }
      },
      "dependencies": {
        "com.example:platform": [],
        "com.example:platform:aar": []
      },
      "packages": {},
      "repositories": {
        "https://repo.example/maven2/": [
          "com.example:platform",
          "com.example:platform:aar"
        ]
      },
      "services": {},
      "skipped": [
        "com.example:platform"
      ],
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

private val POM_PACKAGING_CANONICAL_LOCKFILE = POM_PACKAGING_LOCALHOST_LOCKFILE
    .replace("http://127.0.0.1:12345/r/0/", "https://repo.example/maven2/")
    .replace(
        """
      "skipped": [
        "com.example:already-skipped"
      ],
    """,
        ""
    )

private val POM_PACKAGING_LOCALHOST_LOCKFILE_WITH_BASELINE_POM_SKIPPED = POM_PACKAGING_LOCALHOST_LOCKFILE
    .replace(
        """
      "skipped": [
        "com.example:already-skipped"
      ],
""",
        """
      "skipped": [
        "com.example:already-skipped",
        "com.example:platform:pom"
      ],
"""
    )
