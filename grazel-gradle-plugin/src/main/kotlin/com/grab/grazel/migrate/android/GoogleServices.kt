/*
 * Copyright 2021 Grabtaxi Holdings PTE LTE (GRAB)
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

import com.android.build.gradle.api.BaseVariant
import org.gradle.api.Project
import java.io.File

private const val GOOGLE_SERVICES_JSON = "google-services.json"

/**
 * Given a android binary project, will try to find the google-services.json required for google services integration.
 *
 * The logic is partially inspired from
 * https://github.com/google/play-services-plugins/blob/cce869348a9f4989d4a77bf9595ab6c073a8c441/google-services-plugin/src/main/groovy/com/google/gms/googleservices/GoogleServicesTask.java#L532
 *
 * @param variants The active variants for which google-services.json should be search for.
 * @param project The gradle project instance
 * @return Path to google-services.json file relativized to project. Empty if not found
 */
fun findGoogleServicesJson(variants: List<BaseVariant>, project: Project): String {
    val variantSource = variants
        .asSequence()
        .flatMap { it.sourceSets.asSequence() }
        .flatMap { it.javaDirectories.asSequence() }
        .map { File(it.parent, GOOGLE_SERVICES_JSON) }
        .toList()
        .reversed()
    val projectDirSource = File(project.projectDir, GOOGLE_SERVICES_JSON)
    return (variantSource + projectDirSource)
        .firstOrNull(File::exists)
        ?.let(project::relativePath)
        ?: ""
}