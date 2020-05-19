/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
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