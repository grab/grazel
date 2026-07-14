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

package com.grab.grazel.maven

internal data class MavenPath(
    val coordinates: MavenCoordinates,
    val fileName: String,
) {
    companion object {
        /**
         * Splits a Maven repository-relative path by `/` and derives group/module/version/
         * fileName purely by position: the last segment is the file name, the two before it
         * are version and module, and everything before that (dot-joined) is the group. This
         * is an implicit format contract — it assumes a well-formed `<group-segments>/<module>/
         * <version>/<file>` layout with at least 4 segments — that every other proxy/index
         * function in this package relies on rather than re-deriving.
         */
        fun parse(path: String): MavenPath? {
            val parts = path.split("/")
            if (parts.size < 4) return null
            return MavenPath(
                coordinates = MavenCoordinates(
                    group = parts.dropLast(3).joinToString("."),
                    module = parts[parts.lastIndex - 2],
                    version = parts[parts.lastIndex - 1],
                ),
                fileName = parts.last()
            )
        }
    }
}

internal fun isConcreteMavenArtifactPath(path: String): Boolean {
    if (path.endsWith(".pom") || path.endsWith(".module")) return false
    if (path.endsWith(".sha1") || path.endsWith(".md5") || path.endsWith(".sha256")) return false
    if (path.endsWith("maven-metadata.xml")) return false
    return MavenPath.parse(path) != null
}
