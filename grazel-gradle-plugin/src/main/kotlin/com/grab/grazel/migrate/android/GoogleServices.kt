/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
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