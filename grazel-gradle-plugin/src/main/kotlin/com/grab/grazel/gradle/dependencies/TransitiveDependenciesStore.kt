/*
 * Copyright 2025 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.gradle.dependencies

import java.util.concurrent.ConcurrentHashMap

interface TransitiveDependenciesStore : AutoCloseable {
    operator fun set(shortId: String, transitiveDeps: Set<String>)
    operator fun get(shortId: String): Set<String>
}

class DefaultTransitiveDependenciesStore : TransitiveDependenciesStore {
    private val cache = ConcurrentHashMap<String, Set<String>>()

    override fun set(shortId: String, transitiveDeps: Set<String>) {
        cache[shortId] = transitiveDeps
    }

    override fun get(shortId: String): Set<String> = cache[shortId] ?: emptySet()

    override fun close() {
        cache.clear()
    }
}