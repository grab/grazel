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

internal fun mavenRepositoryUrlWithBasicCredentials(
    url: String,
    username: String,
    password: String,
): String {
    val schemeSeparator = "://"
    val schemeIndex = url.indexOf(schemeSeparator)
    if (schemeIndex < 0) return url
    return url.replaceRange(
        startIndex = schemeIndex,
        endIndex = schemeIndex + schemeSeparator.length,
        replacement = "$schemeSeparator$username:$password@"
    )
}
