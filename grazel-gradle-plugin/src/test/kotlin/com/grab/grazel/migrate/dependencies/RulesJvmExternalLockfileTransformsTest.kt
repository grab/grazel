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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class RulesJvmExternalLockfileTransformsTest {

    @Test
    fun `rewriter restores canonical urls in recursive lockfile keys and string values`() {
        val lockfile = RulesJvmExternalLockfileParser.parse(
            lockfile(
                artifactFields = """"url": "http://127.0.0.1:12345/r/special/com/example/lib.jar",""",
                repositories = """
                    "http://127.0.0.1:12345/r/special/": [
                      "com.example:lib"
                    ]
                """.trimIndent()
            )
        )

        val rewritten = MavenLockfileRepositoryUrlRewriter(
            MavenInstallRepositoryRewrite(
                proxyToCanonicalUrl = mapOf(
                    "http://127.0.0.1:12345/r/" to "https://repo.example/maven2/",
                    "http://127.0.0.1:12345/r/special/" to "https://special.example/maven"
                )
            )
        ).rewrite(lockfile)

        assertThat(rewritten.repositories.keys).containsExactly("https://special.example/maven/")
        assertThat(
            rewritten.artifacts
                .getValue("com.example:lib")
                .jsonObject
                .getValue("url")
                .jsonPrimitive
                .content
        ).isEqualTo("https://special.example/maven/com/example/lib.jar")
    }

    @Test
    fun `baseline merger keeps baseline skipped facts but rejects dropped baseline artifacts`() {
        val current = RulesJvmExternalLockfileParser.parse(
            lockfile(
                artifact = "com.example:baseline",
                skipped = """
                    "com.example:baseline",
                    "com.example:new-skip"
                """.trimIndent()
            )
        )
        val baseline = RulesJvmExternalLockfileParser.parse(
            lockfile(
                artifact = "com.example:baseline",
                skipped = """"com.example:already-skipped""""
            )
        )

        val merged = BaselineLockfileFactsMerger.merge(current, baseline)

        assertThat(merged.skipped!!.map { skipped -> skipped.jsonPrimitive.content })
            .containsExactly("com.example:already-skipped", "com.example:new-skip")
            .inOrder()
    }

    @Test
    fun `hasher recomputes rje input and resolved hashes from parsed lockfile sections`() {
        val lockfile = RulesJvmExternalLockfileParser.parse(lockfile())

        val inputHash = RulesJvmExternalLockfileHasher.inputArtifactsHashWithRepositories(
            inputArtifactsHash = lockfile.inputArtifactsHash,
            canonicalRepositoryInputs = listOf(repositoryInputSpec("https://repo.example/maven2/"))
        )
        val resolvedHash = RulesJvmExternalLockfileHasher.resolvedArtifactsHash(lockfile)

        assertThat(inputHash.getValue("repositories").jsonPrimitive.intOrNull)
            .isEqualTo(1182908442)
        assertThat(resolvedHash.getValue("com.example:lib").jsonPrimitive.intOrNull)
            .isEqualTo(1888541845)
    }

    private fun lockfile(
        artifact: String = "com.example:lib",
        artifactFields: String = "",
        repositories: String = """
            "https://repo.example/maven2/": [
              "$artifact"
            ]
        """.trimIndent(),
        skipped: String? = null,
    ): String {
        val skippedSection = skipped?.let { value ->
            """
              "skipped": [
                $value
              ],
            """.trimIndent()
        }.orEmpty()
        return """
            {
              "$INPUT_ARTIFACTS_HASH_KEY": {
                "$artifact": 1698394405,
                "repositories": -1
              },
              "$RESOLVED_ARTIFACTS_HASH_KEY": {
                "$artifact": -1
              },
              "artifacts": {
                "$artifact": {
                  $artifactFields
                  "shasums": {
                    "jar": "abc123"
                  },
                  "version": "1.0"
                }
              },
              "dependencies": {
                "$artifact": []
              },
              "packages": {
                "$artifact": [
                  "com.example"
                ]
              },
              "repositories": {
                $repositories
              },
              "services": {},
              $skippedSection
              "version": "3"
            }
        """.trimIndent()
    }
}
