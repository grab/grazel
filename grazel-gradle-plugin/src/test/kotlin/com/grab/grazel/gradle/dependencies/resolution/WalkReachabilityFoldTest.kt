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
 * Pins [MainReachabilityTracker.recordReachable]'s union contract: walk-discovered project
 * paths and bucket names are accepted into the tracker's reachability state even when the
 * declared-edge DFS seed ([MainReachabilityTracker.computeScope]) never recorded them — i.e.
 * the fold is a set-union, not an intersection or a seed-gated accept.
 *
 * Background (`reports/review/item2-channel-evidence.md`): a full PAX migrate found the walk
 * fold's dominant real-world delta is a bucket-*name* divergence on paths the seed already
 * reached (74 of 75 walk-only bucket entries on `:apex-cfm:cfm-ui-tests`), plus 1 genuinely
 * new path the seed missed (`:grab-test-recorder`); two other roots
 * (`:comms-ui-tests:hedwig-ui-tests`, `:cx-ui-tests:subscription-ui-tests`) each surface one
 * new sibling test-support path. This test exercises `recordReachable` directly with a
 * synthetic new path, pinning the union contract those deltas depend on.
 *
 * Coverage limit: this test drives `recordReachable` directly — it would NOT fail if someone
 * gated the MAIN-root fold out of `AggregatedDependencyResolver.ResolutionSession.resolve()`
 * (a private inner class, impractical to seam-test directly). The samples golden baseline
 * would not catch that regression either: both sample projects show zero MAIN walk deltas
 * (see the evidence doc). The resolve()-site MAIN-root fold itself has no unit-level guard;
 * it is protected by the PAX verification gates only (samples exhibit zero MAIN walk deltas,
 * so the local golden cannot catch its removal — see reports/review/item2-channel-evidence.md).
 */
class WalkReachabilityFoldTest {

    @Test
    fun `recordReachable unions walk-discovered facts the declared DFS seed never recorded`() {
        val tracker = MainReachabilityTracker(
            declaredDependencyMetadata = DeclaredDependencyMetadata(projects = emptyMap()),
            migratableProjectPaths = listOf(":app", ":ui-test-support")
        )
        tracker.recordReachable(
            projectPaths = setOf(":ui-test-support"),
            bucketNamesByProject = mapOf(":ui-test-support" to setOf("debug"))
        )
        assertEquals(setOf(":ui-test-support"), tracker.reachableMainProjectPaths.toSet())
        assertEquals(
            setOf("debug"),
            tracker.reachableMainBucketNamesByProject[":ui-test-support"].orEmpty().toSet()
        )
    }
}
