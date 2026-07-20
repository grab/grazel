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

/**
 * Generic `shortId -> ResolvedDependency` map-merge primitives, shared by both the resolution
 * (bucket accumulation) and the bucket-ownership-planning layers. These carry no
 * ownership/placement decision logic — they only union maps while keeping the highest version's
 * metadata (via [mergeDependencyMetadataByMaxVersion]) — so they live in this neutral parent
 * package that both layers already depend on, rather than in either subpackage.
 */
package com.grab.grazel.gradle.dependencies

import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.util.merge

internal fun unionDependencyMaps(
    runtime: Map<String, ResolvedDependency>,
    compile: Map<String, ResolvedDependency>
): Map<String, ResolvedDependency> {
    if (compile.isEmpty()) return runtime
    if (runtime.isEmpty()) return compile
    return listOf(runtime, compile).merge(::mergeDependencyMetadataByMaxVersion)
}

internal fun <K> MutableMap<K, Map<String, ResolvedDependency>>.mergeBucket(
    key: K,
    dependencies: Map<String, ResolvedDependency>
) {
    this[key] = this[key]?.let { existing -> unionDependencyMaps(existing, dependencies) } ?: dependencies
}
