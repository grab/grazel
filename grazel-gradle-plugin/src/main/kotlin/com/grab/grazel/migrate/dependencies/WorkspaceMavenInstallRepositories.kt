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

/**
 * Reads the `name` and `repositories = [...]` URL list out of every `maven_install(...)` call in a
 * WORKSPACE file's text, without a real Starlark parser - mirroring the line-oriented depth
 * tracking [MavenInstallWorkspaceRepositoryRewriter] uses. This exists so lockfile reconstruction
 * can hash the exact repository list rules_jvm_external's own repository rule reads from the
 * WORKSPACE at build time - including any repositories a downstream consumer's build wrapper adds
 * or removes after grazel generates the file - rather than grazel's own internal model of what it
 * generated, which a downstream WORKSPACE patch can silently invalidate.
 *
 * The `repositories` list can also concatenate a bare Starlark identifier (e.g.
 * `[...] + DAGGER_REPOSITORIES`) rather than only literal URL strings, since that value is loaded
 * from an external `.bzl` file and isn't inlined into the WORKSPACE text. Such identifiers are
 * expanded via [externalRepositoryUrls] - the same table grazel's own generation-time model uses -
 * so whether the identifier is present in the WORKSPACE (not grazel's internal `hasDagger` flag)
 * decides inclusion, matching what a downstream patch that strips the identifier's text will
 * actually leave for rules_jvm_external to read.
 */
internal object WorkspaceMavenInstallRepositories {
    private val nameRegex = Regex("""^\s*name\s*=\s*"([^"]+)"""")
    private val quotedStringRegex = Regex(""""([^"]+)"""")
    private val identifierRegex = Regex("""\b[A-Z][A-Z0-9_]*\b""")

    fun parse(workspace: String): Map<String, List<String>> {
        val result = linkedMapOf<String, List<String>>()
        val tracker = MavenInstallBlockTracker(result)
        workspace.lineSequence().forEach(tracker::consume)
        return result
    }

    /**
     * Tracks two independent depth counters - one for the enclosing `maven_install(` parens, one
     * for the `repositories[` brackets - the same scoping [MavenInstallWorkspaceRepositoryRewriter]
     * relies on, so URL collection is confined to the `repositories` list of the currently open
     * `maven_install` block rather than any other quoted string in the file (artifacts, overrides,
     * comments). The block's `name` and collected `urls` are committed to [result] only once the
     * enclosing parens close, so a block is attributed to its own name even if `repositories`
     * appears before `name` in the source.
     */
    private class MavenInstallBlockTracker(
        private val result: MutableMap<String, List<String>>,
    ) {
        private var insideMavenInstall = false
        private var mavenInstallParenDepth = 0
        private var insideRepositories = false
        private var repositoriesBracketDepth = 0
        private var name: String? = null
        private val urls = mutableListOf<String>()

        fun consume(line: String) {
            enterMavenInstallIfNeeded(line)
            if (insideMavenInstall && name == null) {
                nameRegex.find(line)?.let { match -> name = match.groupValues[1] }
            }
            enterRepositoriesIfNeeded(line)
            if (insideMavenInstall && insideRepositories) {
                quotedStringRegex.findAll(line).forEach { match -> urls += match.groupValues[1] }
                identifierRegex.findAll(line.replace(quotedStringRegex, ""))
                    .forEach { match -> urls += externalRepositoryUrls(match.value) }
            }
            advanceState(line)
        }

        private fun enterMavenInstallIfNeeded(line: String) {
            if (!insideMavenInstall && line.trimStart().startsWith("maven_install(")) {
                insideMavenInstall = true
            }
        }

        private fun enterRepositoriesIfNeeded(line: String) {
            if (insideMavenInstall && !insideRepositories && line.trimStart().startsWith("repositories")) {
                insideRepositories = true
            }
        }

        private fun advanceState(line: String) {
            if (insideRepositories) {
                repositoriesBracketDepth += line.count { char -> char == '[' }
                repositoriesBracketDepth -= line.count { char -> char == ']' }
                if (repositoriesBracketDepth <= 0) {
                    insideRepositories = false
                    repositoriesBracketDepth = 0
                }
            }
            if (insideMavenInstall) {
                mavenInstallParenDepth += line.count { char -> char == '(' }
                mavenInstallParenDepth -= line.count { char -> char == ')' }
                if (mavenInstallParenDepth <= 0) {
                    name?.let { finishedName -> result[finishedName] = urls.toList() }
                    insideMavenInstall = false
                    mavenInstallParenDepth = 0
                    insideRepositories = false
                    repositoriesBracketDepth = 0
                    name = null
                    urls.clear()
                }
            }
        }
    }
}
