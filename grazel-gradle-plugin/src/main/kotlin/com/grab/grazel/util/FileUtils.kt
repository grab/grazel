/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.util

/**
 * Given a list of file paths like `/src/main`, /src/test`, will return the longest common path (`/src/`) among them
 */
internal fun commonPath(vararg paths: String): String {
    var commonPath = ""
    val folders: Array<Array<String>> = Array(paths.size) {
        emptyArray()
    }
    for (i in paths.indices) {
        folders[i] = paths[i].split("/").toTypedArray()
    }
    for (j in folders[0].indices) {
        val s = folders[0][j]
        for (i in 1 until paths.size) {
            if (s != folders[i][j]) return commonPath
        }
        commonPath += "$s/"
    }
    return commonPath
}