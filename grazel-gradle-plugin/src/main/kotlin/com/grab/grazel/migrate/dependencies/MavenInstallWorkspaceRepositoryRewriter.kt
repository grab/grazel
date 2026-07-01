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
    private val repositoriesBlockRegex = Regex(
        pattern = """(?s)(repositories\s*=\s*\[)(.*?)(\])"""
    )
    private val quotedStringRegex = Regex(""""([^"]+)"""")

    fun rewrite(
        workspace: String,
        urlReplacements: Map<String, String>,
    ): String {
        if (urlReplacements.isEmpty()) return workspace
        return repositoriesBlockRegex.replace(workspace) { blockMatch ->
            val blockBody = blockMatch.groupValues[2]
            val rewrittenBody = quotedStringRegex.replace(blockBody) { urlMatch ->
                val url = urlMatch.groupValues[1]
                urlReplacements[url]?.let { replacement -> """"$replacement"""" }
                    ?: urlMatch.value
            }
            blockMatch.groupValues[1] + rewrittenBody + blockMatch.groupValues[3]
        }
    }
}
