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

internal object MavenInstallWorkspaceRepositoryRewriter {
    fun rewrite(
        workspace: String,
        urlReplacements: Map<String, String>,
    ): String {
        if (urlReplacements.isEmpty()) return workspace
        val rewriter = MavenInstallRepositoryBlockRewriter(urlReplacements)
        return workspace
            .lineSequence()
            .joinToString(separator = "\n") { line -> rewriter.rewrite(line) }
    }
}

private val quotedStringRegex = Regex(""""([^"]+)"""")

private class MavenInstallRepositoryBlockRewriter(
    private val urlReplacements: Map<String, String>,
) {
    private var insideMavenInstall = false
    private var mavenInstallParenDepth = 0
    private var insideRepositories = false
    private var repositoriesBracketDepth = 0

    fun rewrite(line: String): String {
        enterMavenInstallIfNeeded(line)
        enterRepositoriesIfNeeded(line)
        val rewrittenLine = if (insideMavenInstall && insideRepositories) {
            rewriteQuotedUrls(line)
        } else {
            line
        }
        advanceState(line)
        return rewrittenLine
    }

    private fun enterMavenInstallIfNeeded(line: String) {
        if (!insideMavenInstall && line.trimStart().startsWith("maven_install(")) {
            insideMavenInstall = true
        }
    }

    private fun enterRepositoriesIfNeeded(line: String) {
        if (
            insideMavenInstall &&
            !insideRepositories &&
            line.trimStart().startsWith("repositories")
        ) {
            insideRepositories = true
        }
    }

    private fun rewriteQuotedUrls(line: String): String =
        quotedStringRegex.replace(line) { urlMatch ->
            val url = urlMatch.groupValues[1]
            urlReplacements[url]?.let { replacement -> """"$replacement"""" }
                ?: urlMatch.value
        }

    private fun advanceState(line: String) {
        if (insideMavenInstall) {
            mavenInstallParenDepth += line.count { char -> char == '(' }
            mavenInstallParenDepth -= line.count { char -> char == ')' }
        }
        if (insideRepositories) {
            repositoriesBracketDepth += line.count { char -> char == '[' }
            repositoriesBracketDepth -= line.count { char -> char == ']' }
            if (repositoriesBracketDepth <= 0) {
                insideRepositories = false
                repositoriesBracketDepth = 0
            }
        }
        if (insideMavenInstall && mavenInstallParenDepth <= 0) {
            insideMavenInstall = false
            mavenInstallParenDepth = 0
            insideRepositories = false
            repositoriesBracketDepth = 0
        }
    }
}
