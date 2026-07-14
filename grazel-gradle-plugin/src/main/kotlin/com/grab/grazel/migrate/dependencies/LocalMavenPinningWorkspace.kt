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

import com.grab.grazel.gradle.dependencies.BASE_MAVEN_REPO
import java.io.File

internal class LocalMavenPinningWorkspace(
    private val workspaceFile: File,
    private val rootDirectory: File,
    private val repositoryRewrite: MavenInstallRepositoryRewrite,
    private val repositoryInputs: MavenInstallRepositoryInputs = MavenInstallRepositoryInputs(emptyMap()),
    private val metadataOnlyShortIds: Set<String> = emptySet(),
) {
    /**
     * Runs [block] with the WORKSPACE's `maven_install` repository URLs temporarily rewritten to
     * point at local proxy servers (and any metadata-only `override_targets` entries - see
     * [metadataOnlyShortIds] - stripped, since those targets don't exist under the proxy). This is
     * necessary because RJE's pin scripts capture whatever repository URLs are live in the
     * WORKSPACE at generation time; running them against the proxy is what allows local Maven
     * resolution to intercept artifact fetches. The original text is captured before rewriting and
     * restored in `finally` regardless of how [block] completes, so any code that runs after this
     * call (e.g. lockfile hashing/reconstruction against canonical URLs) sees the real WORKSPACE -
     * losing that restore would leave proxy URLs baked into the checked-in WORKSPACE.
     */
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

    /**
     * Must run after the WORKSPACE has been restored to canonical URLs (i.e. after
     * [withProxyRepositories] returns) - the reconstructor rewrites proxy URLs recorded in the
     * just-generated lockfile back to canonical using [repositoryInputs], so it needs the canonical
     * repository inputs, not the proxy ones. Failing with [error] when a repo has no
     * [MavenInstallRepositoryInputs] entry is intentional: silently skipping reconstruction for that
     * repo would leave proxy URLs or an unreconciled baseline in its lockfile.
     */
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
    if (repoName == BASE_MAVEN_REPO) {
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

/**
 * Line-by-line brace-depth tracker that removes specific entries from a WORKSPACE
 * `override_targets = { ... }` dict without parsing Starlark. It only tracks depth once inside the
 * block (triggered by a line starting with `override_targets`) and closes the block when brace
 * depth returns to zero, so it assumes each dict entry's braces (if any) are balanced within the
 * lines they appear on and that `override_targets` is not nested inside another brace-containing
 * construct on the same opening line. Formatting that violates those assumptions (e.g. a value
 * spanning multiple unbalanced lines, or nested dicts sharing a line with the `override_targets`
 * keyword) will mis-scope the removal.
 */
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

/**
 * Heuristically identifies an `override_targets` dict-entry key line of the form `"<short-id>":`
 * without a real parser. Assumes the key is the first quoted string on the line and that it is
 * immediately followed (modulo whitespace) by a colon - true for rules_jvm_external's own
 * pretty-printed WORKSPACE dict entries, but not a general Starlark string/dict grammar (e.g. a
 * quoted string used as a value rather than a key on its own line would be misread as a key).
 */
private fun overrideTargetKey(line: String): String? {
    val trimmed = line.trimStart()
    if (!trimmed.startsWith('"')) return null
    val closingQuoteIndex = trimmed.indexOf('"', startIndex = 1)
    if (closingQuoteIndex <= 1) return null
    if (!trimmed.drop(closingQuoteIndex + 1).trimStart().startsWith(':')) return null
    return trimmed.substring(1, closingQuoteIndex)
}
