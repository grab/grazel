/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate.android

import com.android.build.gradle.BaseExtension
import com.android.build.gradle.api.AndroidSourceSet
import groovy.util.XmlSlurper
import groovy.util.slurpersupport.NodeChild
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal interface AndroidManifestParser {
    fun parsePackageName(extension: BaseExtension, androidSourceSets: List<AndroidSourceSet>): String?
    fun androidManifestFile(sourceSets: List<AndroidSourceSet>): File?
}

@Singleton
internal class DefaultAndroidManifestParser @Inject constructor() : AndroidManifestParser {
    /**
     * Parse Android package name from [BaseExtension] by looking in [BaseExtension.defaultConfig] or by parsing
     * the `AndroidManifest.xml`
     */
    override fun parsePackageName(extension: BaseExtension, androidSourceSets: List<AndroidSourceSet>): String? {
        val packageName = extension.defaultConfig.applicationId // TODO(arun) Handle suffixes
        return if (packageName == null) {
            // Try parsing from AndroidManifest.xml
            val manifestFile = androidManifestFile(androidSourceSets) ?: return null
            XmlSlurper().parse(manifestFile)
                .list()
                .filterIsInstance<NodeChild>()
                .firstOrNull { it.name() == "manifest" }
                ?.attributes()?.get("package")?.toString()
        } else packageName
    }

    override fun androidManifestFile(
        sourceSets: List<AndroidSourceSet>
    ): File? = sourceSets
        .map { it.manifest.srcFile }
        .last(File::exists) // Pick the last one since AGP gives source set in ascending order. See `BaseVariant.sourceSets`
}