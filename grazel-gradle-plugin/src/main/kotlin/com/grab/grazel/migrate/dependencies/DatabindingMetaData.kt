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

import com.grab.grazel.bazel.starlark.toBazelPath
import com.grab.grazel.maven.MavenCoordinates
import java.io.File
import java.util.zip.ZipFile

private const val BR_BIN = "-br.bin"

/**
 * The Bazel target name `--android_databinding_package_info` keys an artifact's databinding package
 * by. Matches the identifier `BazelDependency.MavenDependency` renders for the same coordinates.
 */
internal fun MavenCoordinates.databindingBazelName(): String =
    "${group.toBazelPath()}_${module.toBazelPath()}"

/**
 * Reads the databinding package an `aar` declares, or `null` when it declares none.
 *
 * The package is the file-name stem of the artifact's `-br.bin` entry. Entries are
 * walked lazily so the scan stops at the first match instead of reading the whole archive; an
 * artifact carrying several such entries resolves to the first one in archive order.
 */
internal fun databindingPackage(aar: File): String? = ZipFile(aar).use { zip ->
    zip.entries()
        .asSequence()
        .filter { entry -> entry.name.endsWith(BR_BIN) }
        .map { entry -> entry.name.substringBefore(BR_BIN).substringAfterLast('/') }
        .firstOrNull()
}

/**
 * Renders the `databinding_info.bazelrc` contents for the given Bazel-name-to-package mapping,
 * preserving the map's iteration order. An empty mapping still renders the flag, with no value.
 */
internal fun renderDatabindingInfoBazelrc(packagesByBazelName: Map<String, String>): String {
    val packageInfo = packagesByBazelName.entries
        .joinToString(separator = ",") { (bazelName, databindingPackage) ->
            "$bazelName=$databindingPackage"
        }
    return """
        |# Generated file. DO NOT MODIFY.
        |build --android_databinding_package_info=$packageInfo
        """.trimMargin()
}
