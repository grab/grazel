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

/**
 * Counters produced by the local Maven proxy while serving artifact requests and
 * consumed by the pinning layer for reporting. Shared by both layers so a new
 * counter only has to be declared once.
 */
internal data class LocalMavenResolutionStats(
    val artifactHits: Long = 0,
    val knownComponentFallthroughs: Long = 0,
    val metadataOnlyArtifactFallbacks: Long = 0,
    val gradlePomHits: Long = 0,
    val originFallbacks: Long = 0,
    val originMisses: Long = 0,
    val requestFailures: Long = 0,
    val checksumHits: Long = 0,
    val writeThroughCacheHits: Long = 0,
    val bytesServed: Long = 0,
)
