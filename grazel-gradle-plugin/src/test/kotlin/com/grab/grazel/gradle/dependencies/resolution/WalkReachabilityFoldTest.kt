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

package com.grab.grazel.gradle.dependencies.resolution

import com.grab.grazel.gradle.dependencies.DeclaredDependencyMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the item-2 finding (`reports/review/item2-channel-evidence.md`): the walk-discovered
 * reachability fold is LOAD-BEARING for MAIN roots. In a full PAX migrate, 3 of 116
 * `GRAZEL-ITEM2 MAIN` roots had non-empty `walkOnlyPaths`/`walkOnlyBuckets` — all
 * `*-ui-tests` support modules whose resolved-graph walk surfaced a transitively-reachable
 * test-support project module that the declared-edge DFS seed did not:
 *
 * ```
 * GRAZEL-ITEM2 MAIN root=:apex-cfm:cfm-ui-tests bucket=default walkOnlyPaths=1:[:grab-test-recorder] ...
 * GRAZEL-ITEM2 MAIN root=:comms-ui-tests:hedwig-ui-tests bucket=default walkOnlyPaths=0 walkOnlyBuckets=1:[:comms-ui-tests:common-ui-tests=[debug]] ...
 * GRAZEL-ITEM2 MAIN root=:cx-ui-tests:subscription-ui-tests bucket=default walkOnlyPaths=0 walkOnlyBuckets=1:[:subscriptions:subscription-test-common=[debug]] ...
 * ```
 *
 * So [MainReachabilityTracker.recordReachable] must accept paths/buckets the declared-edge
 * DFS never seeded. Deleting the fold on the grounds "the DFS already covers it" is a
 * regression: it is a no-op for most roots but load-bearing for these `*-ui-tests` roots.
 */
class WalkReachabilityFoldTest {

    @Test
    fun `recordReachable folds walk-discovered facts the declared DFS did not seed`() {
        val tracker = MainReachabilityTracker(
            declaredDependencyMetadata = DeclaredDependencyMetadata(projects = emptyMap()),
            migratableProjectPaths = listOf(":app", ":substituted-lib")
        )
        tracker.recordReachable(
            projectPaths = setOf(":substituted-lib"),
            bucketNamesByProject = mapOf(":substituted-lib" to setOf("debug"))
        )
        assertEquals(setOf(":substituted-lib"), tracker.reachableMainProjectPaths.toSet())
        assertEquals(
            setOf("debug"),
            tracker.reachableMainBucketNamesByProject[":substituted-lib"].orEmpty().toSet()
        )
    }
}
