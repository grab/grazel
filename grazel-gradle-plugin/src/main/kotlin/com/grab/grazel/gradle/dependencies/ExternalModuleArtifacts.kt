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

package com.grab.grazel.gradle.dependencies

import com.grab.grazel.maven.MavenCoordinates
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult

/**
 * The external (non-project) artifacts of this configuration.
 *
 * Lenient on purpose: every consumer here is a best-effort mirror of what Gradle resolved, and an
 * artifact that fails to resolve must never fail the migration. Keep this the single definition of
 * that view — consumers that each spell out their own `artifactView` drift apart the moment the
 * filter changes.
 */
internal fun Configuration.externalModuleArtifacts(): ArtifactCollection = incoming
    .artifactView {
        isLenient = true
        componentFilter { identifier -> identifier is ModuleComponentIdentifier }
    }.artifacts

/** Maven coordinates of this artifact, or `null` when it is not an external module artifact. */
internal fun ResolvedArtifactResult.mavenCoordinates(): MavenCoordinates? =
    (id.componentIdentifier as? ModuleComponentIdentifier)?.toMavenCoordinates()

internal fun ModuleComponentIdentifier.toMavenCoordinates() = MavenCoordinates(
    group = group,
    module = module,
    version = version
)
