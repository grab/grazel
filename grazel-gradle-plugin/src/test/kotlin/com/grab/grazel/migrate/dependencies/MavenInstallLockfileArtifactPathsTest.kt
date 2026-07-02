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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MavenInstallLockfileArtifactPathsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `derives exact concrete artifact paths from rje v3 lockfile entries`() {
        val facts = mavenInstallLockfileFallbackFacts(
            """
                {
                  "artifacts": {
                    "com.example:library": {
                      "shasums": {
                        "jar": "abc",
                        "sources": "def"
                      },
                      "version": "1.0"
                    },
                    "com.example:android-lib:aar": {
                      "shasums": {
                        "jar": "123"
                      },
                      "version": "2.0"
                    },
                    "com.example:platform:pom": {
                      "shasums": {
                        "jar": null
                      },
                      "version": "3.0"
                    },
                    "com.example:skipped": {
                      "shasums": {
                        "jar": null
                      },
                      "version": "4.0"
                    }
                  },
                  "dependencies": {},
                  "packages": {},
                  "repositories": {},
                  "services": {},
                  "version": "3"
                }
            """.trimIndent()
        )

        assertThat(facts.paths).containsExactly(
            "com/example/android-lib/2.0/android-lib-2.0.aar",
            "com/example/library/1.0/library-1.0-sources.jar",
            "com/example/library/1.0/library-1.0.jar",
            "com/example/platform/3.0/platform-3.0.pom"
        )
    }

    @Test
    fun `derives gavs from rje v3 lockfile entries`() {
        val facts = mavenInstallLockfileFallbackFacts(
            """
                {
                  "artifacts": {
                    "com.example:library": {
                      "shasums": {
                        "jar": "abc"
                      },
                      "version": "1.0"
                    },
                    "com.example:android-lib:aar": {
                      "shasums": {
                        "jar": "123"
                      },
                      "version": "2.0"
                    },
                    "com.example:platform:pom": {
                      "shasums": {
                        "jar": null
                      },
                      "version": "3.0"
                    }
                  },
                  "dependencies": {},
                  "packages": {},
                  "repositories": {},
                  "services": {},
                  "version": "3"
                }
            """.trimIndent()
        )

        assertThat(facts.gavs).containsExactly(
            "com.example:android-lib:2.0",
            "com.example:library:1.0",
            "com.example:platform:3.0"
        )
    }

    @Test
    fun `active lockfile artifact paths only read active maven repos`() {
        temporaryFolder.newFile(mavenInstallJsonName("maven")).writeText(
            lockfileWithArtifact("com.example:active", "1.0")
        )
        temporaryFolder.newFile(mavenInstallJsonName("debug_maven")).writeText(
            lockfileWithArtifact("com.example:debug-only", "2.0")
        )
        temporaryFolder.newFile(mavenInstallJsonName("release_maven")).writeText(
            lockfileWithArtifact("com.example:stale", "3.0")
        )

        assertThat(
            activeMavenInstallLockfileFallbackFacts(
                rootDirectory = temporaryFolder.root,
                activeMavenRepos = setOf("maven", "debug_maven")
            ).paths
        ).containsExactly(
            "com/example/active/1.0/active-1.0.jar",
            "com/example/debug-only/2.0/debug-only-2.0.jar"
        )
    }
}

private fun lockfileWithArtifact(
    artifactKey: String,
    version: String,
): String = """
    {
      "artifacts": {
        "$artifactKey": {
          "shasums": {
            "jar": "abc"
          },
          "version": "$version"
        }
      },
      "dependencies": {},
      "packages": {},
      "repositories": {},
      "services": {},
      "version": "3"
    }
""".trimIndent()
