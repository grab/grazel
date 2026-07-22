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

package com.grab.grazel.migrate.target

import com.grab.grazel.gradle.dependencies.merged
import com.grab.grazel.gradle.dependencies.model.TargetReferenceFacts
import com.grab.grazel.migrate.TargetBuilder
import org.gradle.api.Project
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class TargetReferenceFactsExtractor
@Inject
constructor(
    private val targetBuilders: Set<@JvmSuppressWildcards TargetBuilder>
) {

    /**
     * Reference facts for [project], derived from exactly the data its target builders would
     * render ([TargetBuilder.selectData]) — dispatch, selection, and data are all shared with
     * the render pass, so facts/render divergence is structurally impossible.
     */
    fun collect(project: Project): TargetReferenceFacts =
        targetBuilders
            .filter { builder -> builder.canHandle(project) }
            .flatMap { builder -> builder.selectData(project) }
            .map(TargetData::referenceFacts)
            .merged()
}
