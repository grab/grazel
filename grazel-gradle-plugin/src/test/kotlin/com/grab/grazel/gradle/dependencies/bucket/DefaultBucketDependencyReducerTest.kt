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
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultBucketDependencyReducerTest {

    @Test
    fun `removes non default dependency already owned by default`() {
        val defaultDependency = dependency("com.example:library:1.0", "maven")
        val debugDependency = dependency("com.example:library:1.0", "debug_maven")

        val reduced = DefaultBucketDependencyReducer().reduce(
            mapOf(
                DEFAULT_VARIANT to mapOf(defaultDependency.shortId to defaultDependency),
                "debug" to mapOf(debugDependency.shortId to debugDependency)
            )
        )

        assertEquals(
            mapOf(
                DEFAULT_VARIANT to mapOf(defaultDependency.shortId to defaultDependency),
                "debug" to emptyMap()
            ),
            reduced
        )
    }

    @Test
    fun `keeps declared non default dependency when default only owns resolved artifact`() {
        val defaultDependency = dependency("com.example:library:1.0", "maven")
        val declaredDebugDependency = dependency("com.example:library:1.0", "Declared")

        val reduced = DefaultBucketDependencyReducer().reduce(
            mapOf(
                DEFAULT_VARIANT to mapOf(defaultDependency.shortId to defaultDependency),
                "debug" to mapOf(declaredDebugDependency.shortId to declaredDebugDependency)
            )
        )

        assertEquals(
            mapOf(
                DEFAULT_VARIANT to mapOf(defaultDependency.shortId to defaultDependency),
                "debug" to mapOf(declaredDebugDependency.shortId to declaredDebugDependency)
            ),
            reduced
        )
    }

    private fun dependency(id: String, repository: String): ResolvedDependency =
        ResolvedDependency.fromId(id, repository)
}
