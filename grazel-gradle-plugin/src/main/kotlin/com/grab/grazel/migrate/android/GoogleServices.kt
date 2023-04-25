/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.migrate.android

import com.grab.grazel.gradle.variant.MatchedVariant
import org.gradle.api.Project
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val GOOGLE_SERVICES_JSON = "google-services.json"

/**
 * Extracts `GOOGLE_SERVICES_JSON` from given variant
 */
internal interface GoogleServicesJsonExtractor {
    /**
     * For given `matchedVariant` find the google-services.json from all valid source sets and return
     * the path.
     *
     * @param project The project to search for, usually an android binary project
     * @param matchedVariant The matched variant
     */
    fun extract(
        project: Project,
        matchedVariant: MatchedVariant
    ): String
}

@Singleton
internal class DefaultGoogleServicesJsonExtractor
@Inject
constructor() : GoogleServicesJsonExtractor {

    override fun extract(
        project: Project,
        matchedVariant: MatchedVariant
    ): String {
        /**
         * The logic is partially inspired from
         * https://github.com/google/play-services-plugins/blob/cce869348a9f4989d4a77bf9595ab6c073a8c441/google-services-plugin/src/main/groovy/com/google/gms/googleservices/GoogleServicesTask.java#L532
         */
        val variantSources = matchedVariant.variant.sourceSets.asSequence()
            .map { File(it.manifestFile.parent, GOOGLE_SERVICES_JSON) }
            .toList()
            .reversed()
        val projectDirSource = File(project.projectDir, GOOGLE_SERVICES_JSON)
        return (variantSources + projectDirSource)
            .firstOrNull(File::exists)
            ?.let(project::relativePath)
            ?: ""
    }
}