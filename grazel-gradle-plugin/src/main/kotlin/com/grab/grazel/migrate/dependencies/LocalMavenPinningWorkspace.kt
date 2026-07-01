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

import java.io.File

internal data class LocalMavenResolutionPinContext(
    val repositoryRewrite: MavenInstallRepositoryRewrite,
    val metadataOnlyShortIds: Set<String>,
    val stats: () -> LocalMavenProxyStats,
)

internal class LocalMavenPinningWorkspace(
    private val workspaceFile: File,
    private val rootDirectory: File,
    private val repositoryRewrite: MavenInstallRepositoryRewrite,
    private val repositoryInputs: MavenInstallRepositoryInputs = MavenInstallRepositoryInputs(emptyMap()),
    private val metadataOnlyShortIds: Set<String> = emptySet(),
) {
    fun <T> withProxyRepositories(block: () -> T): T {
        val workspace = workspaceFile.readText()
        workspaceFile.writeText(
            MavenInstallWorkspaceRepositoryRewriter.rewrite(
                workspace = workspace.withoutOverrideTargets(metadataOnlyShortIds),
                urlReplacements = repositoryRewrite.canonicalToProxyUrl()
            )
        )
        return try {
            block()
        } finally {
            workspaceFile.writeText(workspace)
        }
    }

    fun reconstructActiveLockfiles(activeMavenRepos: Set<String>) {
        val reconstructor = MavenInstallLockfileReconstructor(repositoryRewrite)
        activeMavenRepos
            .map { repoName -> repoName to rootDirectory.resolve(repoName.mavenInstallJsonName()) }
            .filter { (_, lockfile) -> lockfile.exists() }
            .forEach { (repoName, lockfile) ->
                lockfile.writeText(
                    reconstructor.reconstruct(
                        lockfileContents = lockfile.readText(),
                        canonicalRepositoryInputs = repositoryInputs.repositoriesByName[repoName]
                            ?: error("Missing maven_install repository inputs for $repoName")
                    )
                )
            }
    }

}

private fun MavenInstallRepositoryRewrite.canonicalToProxyUrl(): Map<String, String> =
    proxyToCanonicalUrl.entries.associate { (proxyUrl, canonicalUrl) -> canonicalUrl to proxyUrl }

private fun String.mavenInstallJsonName(): String =
    if (this == "maven") {
        "maven_install.json"
    } else {
        "${this}_install.json"
    }

private fun String.withoutOverrideTargets(shortIds: Set<String>): String {
    if (shortIds.isEmpty()) return this
    return lineSequence()
        .filterNot { line ->
            val overrideTargetKey = line.overrideTargetKey()
            overrideTargetKey != null && overrideTargetKey in shortIds
        }
        .joinToString(separator = "\n", postfix = "\n")
}

private fun String.overrideTargetKey(): String? {
    val trimmed = trimStart()
    if (!trimmed.startsWith('"')) return null
    val closingQuoteIndex = trimmed.indexOf('"', startIndex = 1)
    if (closingQuoteIndex <= 1) return null
    if (!trimmed.drop(closingQuoteIndex + 1).trimStart().startsWith(':')) return null
    return trimmed.substring(1, closingQuoteIndex)
}
