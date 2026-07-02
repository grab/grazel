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
                workspace = workspaceWithoutMetadataOnlyOverrideTargets(
                    workspace = workspace,
                    shortIds = metadataOnlyShortIds
                ),
                urlReplacements = repositoryRewrite.canonicalToProxyUrl
            )
        )
        return try {
            block()
        } finally {
            workspaceFile.writeText(workspace)
        }
    }

    fun snapshotActiveLockfiles(activeMavenRepos: Set<String>): Map<String, String> =
        activeLockfiles(activeMavenRepos)
            .associate { (repoName, lockfile) -> repoName to lockfile.readText() }

    fun reconstructActiveLockfiles(
        activeMavenRepos: Set<String>,
        baselineLockfilesByRepoName: Map<String, String> = emptyMap(),
    ) {
        val reconstructor = MavenInstallLockfileReconstructor(repositoryRewrite)
        activeLockfiles(activeMavenRepos)
            .forEach { (repoName, lockfile) ->
                lockfile.writeText(
                    reconstructor.reconstruct(
                        lockfileContents = lockfile.readText(),
                        canonicalRepositoryInputs = repositoryInputs.repositoriesByName[repoName]
                            ?.map { input -> input.repositoryInputSpec }
                            ?: error("Missing maven_install repository inputs for $repoName"),
                        baselineLockfileContents = baselineLockfilesByRepoName[repoName],
                        requireBaselineForPomPackagingArtifacts = true
                    )
                )
            }
    }

    private fun activeLockfiles(activeMavenRepos: Set<String>): Sequence<Pair<String, File>> =
        activeMavenRepos
            .asSequence()
            .map { repoName -> repoName to rootDirectory.resolve(mavenInstallJsonName(repoName)) }
            .filter { (_, lockfile) -> lockfile.exists() }

}

internal fun mavenInstallJsonName(repoName: String): String =
    if (repoName == "maven") {
        "maven_install.json"
    } else {
        "${repoName}_install.json"
    }

private fun workspaceWithoutMetadataOnlyOverrideTargets(
    workspace: String,
    shortIds: Set<String>,
): String {
    if (shortIds.isEmpty()) return workspace
    val overrideTargetsFilter = OverrideTargetsFilter(removedShortIds = shortIds)
    return workspace.lineSequence()
        .filter(overrideTargetsFilter::shouldKeep)
        .joinToString(separator = "\n", postfix = "\n")
}

private class OverrideTargetsFilter(
    private val removedShortIds: Set<String>,
) {
    private var insideOverrideTargets = false
    private var braceDepth = 0

    fun shouldKeep(line: String): Boolean {
        enterOverrideTargetsBlockIfNeeded(line)
        val overrideTargetKey = if (insideOverrideTargets) overrideTargetKey(line) else null
        val keepLine = overrideTargetKey == null || overrideTargetKey !in removedShortIds
        advanceBlockState(line)
        return keepLine
    }

    private fun enterOverrideTargetsBlockIfNeeded(line: String) {
        if (!insideOverrideTargets && line.trimStart().startsWith("override_targets")) {
            insideOverrideTargets = true
        }
    }

    private fun advanceBlockState(line: String) {
        if (!insideOverrideTargets) return
        braceDepth += line.count { char -> char == '{' }
        braceDepth -= line.count { char -> char == '}' }
        if (braceDepth <= 0) {
            insideOverrideTargets = false
            braceDepth = 0
        }
    }
}

private fun overrideTargetKey(line: String): String? {
    val trimmed = line.trimStart()
    if (!trimmed.startsWith('"')) return null
    val closingQuoteIndex = trimmed.indexOf('"', startIndex = 1)
    if (closingQuoteIndex <= 1) return null
    if (!trimmed.drop(closingQuoteIndex + 1).trimStart().startsWith(':')) return null
    return trimmed.substring(1, closingQuoteIndex)
}
