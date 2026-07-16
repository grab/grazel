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

package com.grab.grazel.gradle.dependencies.bucket

import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import java.util.stream.Collectors

/**
 * Streams all non-default buckets in [classPaths] in parallel, filters out empty ones, and applies
 * [perBucketTransform] (receiving the default bucket and the current bucket) to produce a mutable
 * concurrent map keyed by bucket name. The default bucket is NOT included in the returned map — it
 * is the caller's responsibility to append it via `.apply { put(DEFAULT_VARIANT, default) }` (or an
 * equivalent key) so callers control the value type at that key.
 */
internal fun <V> reduceNonDefaultBuckets(
    classPaths: Map<String, Map<String, ResolvedDependency>>,
    defaultKey: String = DEFAULT_VARIANT,
    perBucketTransform: (
        default: Map<String, ResolvedDependency>,
        bucket: Map<String, ResolvedDependency>
    ) -> Map<String, V>
): MutableMap<String, Map<String, V>> {
    val default = classPaths.getValue(defaultKey)
    return classPaths.entries.parallelStream()
        .filter { it.key != defaultKey }
        .filter { it.value.isNotEmpty() }
        .collect(
            Collectors.toConcurrentMap(
                { it.key },
                { (_, bucket) -> perBucketTransform(default, bucket) }
            )
        )
}
