/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.grab.grazel.gradle.Repository
import java.io.File
import java.net.URI

internal class UrlRewriter {
    fun generate(
        outputFile: File,
        allRepositories: Set<Repository>
    ) {
        require(outputFile.exists()) {
            "Output file ${outputFile.absolutePath} does not exist"
        }
        outputFile.writeText(
            allRepositories
                .asSequence()
                .filter { it.username != null && it.password != null }
                .map { repo ->
                    URI(repo.url).let { uri ->
                        val path = "${uri.host}${uri.path}"
                        "rewrite $path/(.*) ${repo.username}:${repo.password}@$path/$1"
                    }
                }.sorted().joinToString(separator = "\n")
        )
    }
}