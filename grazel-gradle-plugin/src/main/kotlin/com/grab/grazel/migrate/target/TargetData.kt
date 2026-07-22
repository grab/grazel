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

import com.grab.grazel.gradle.dependencies.TargetReferenceFactsCollector
import com.grab.grazel.gradle.dependencies.model.TargetReferenceFacts
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.migrate.android.AndroidBinaryData
import com.grab.grazel.migrate.android.AndroidInstrumentationBinaryData
import com.grab.grazel.migrate.android.AndroidLibraryData
import com.grab.grazel.migrate.android.AndroidTestData
import com.grab.grazel.migrate.android.AndroidUnitTestData
import com.grab.grazel.migrate.kotlin.KotlinProjectData
import com.grab.grazel.migrate.kotlin.UnitTestData

/**
 * A unit of selected target data: what a [com.grab.grazel.migrate.TargetBuilder] decided to
 * generate for a project, before rendering. The single seam between selection (builder-owned)
 * and the reference-facts pass — facts are derived from the same objects the builders render,
 * making facts/render divergence structurally impossible.
 */
internal sealed interface TargetData {
    fun referenceFacts(): TargetReferenceFacts
}

internal data class AndroidLibraryTargetData(
    val data: AndroidLibraryData
) : TargetData {
    override fun referenceFacts(): TargetReferenceFacts = TargetReferenceFactsCollector.from(
        deps = data.deps,
        tags = data.tags,
        plugins = data.plugins,
        lintChecks = data.lintConfigData.lintChecks.orEmpty()
    )
}

internal data class AndroidUnitTestTargetData(
    val data: AndroidUnitTestData
) : TargetData {
    override fun referenceFacts(): TargetReferenceFacts = TargetReferenceFactsCollector.from(
        deps = data.deps,
        tags = data.tags,
        associates = data.associates
    )
}

/**
 * Binary selection carries the matched variant alongside both extracted data objects:
 * `build()` needs the variant for target naming and crashlytics decoration, while
 * [referenceFacts] merges the two dep lists exactly as the old `androidBinaryReferenceFacts`
 * did. Crashlytics deps are deliberately absent here — they are render-only decoration
 * (intra-package label) and were never part of the facts.
 */
internal data class AndroidBinaryVariantTargetData(
    val matchedVariant: MatchedVariant,
    val libraryData: AndroidLibraryData,
    val binaryData: AndroidBinaryData
) : TargetData {
    override fun referenceFacts(): TargetReferenceFacts = TargetReferenceFactsCollector.from(
        deps = libraryData.deps + binaryData.deps,
        plugins = libraryData.plugins,
        lintChecks = libraryData.lintConfigData.lintChecks.orEmpty()
    )
}

internal data class AndroidInstrumentationTargetData(
    val data: AndroidInstrumentationBinaryData
) : TargetData {
    override fun referenceFacts(): TargetReferenceFacts = TargetReferenceFactsCollector.from(
        deps = data.deps,
        tags = data.tags,
        associates = data.associates,
        instruments = data.instruments
    )
}

internal data class AndroidTestTargetData(
    val data: AndroidTestData
) : TargetData {
    override fun referenceFacts(): TargetReferenceFacts = TargetReferenceFactsCollector.from(
        deps = data.deps,
        tags = data.tags,
        lintChecks = data.lintConfigData.lintChecks.orEmpty(),
        associates = data.associates,
        instruments = data.instruments
    )
}

internal data class KotlinLibraryTargetData(
    val data: KotlinProjectData
) : TargetData {
    override fun referenceFacts(): TargetReferenceFacts = TargetReferenceFactsCollector.from(
        deps = data.deps,
        tags = data.tags,
        plugins = data.plugins,
        lintChecks = data.lintConfigData.lintChecks.orEmpty()
    )
}

internal data class KotlinUnitTestTargetData(
    val data: UnitTestData
) : TargetData {
    override fun referenceFacts(): TargetReferenceFacts = TargetReferenceFactsCollector.from(
        deps = data.deps,
        tags = data.tags,
        associates = data.associates
    )
}
