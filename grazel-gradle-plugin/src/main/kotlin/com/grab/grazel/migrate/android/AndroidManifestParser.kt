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