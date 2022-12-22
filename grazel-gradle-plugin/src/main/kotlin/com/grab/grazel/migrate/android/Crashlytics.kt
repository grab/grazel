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

import com.android.build.gradle.BaseExtension
import com.android.build.gradle.api.AndroidSourceSet
import com.grab.grazel.GrazelExtension
import com.grab.grazel.gradle.AndroidVariantDataSource
import com.grab.grazel.gradle.getMigratableBuildVariants
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import javax.inject.Inject
import javax.inject.Singleton

internal data class CrashlyticsData(
    val packageName: String?,
    val buildId: String?,
    val googleServicesJson: String?,
)

internal interface CrashlyticsDataExtractor {
    fun extract(
        project: Project,
        androidSourceSets: List<AndroidSourceSet>,
    ): CrashlyticsData
}

@Singleton
internal class DefaultCrashlyticsDataExtractor @Inject constructor(
    private val variantDataSource: AndroidVariantDataSource,
    private val grazelExtension: GrazelExtension,
    private val androidManifestParser: AndroidManifestParser,
) : CrashlyticsDataExtractor {

    override fun extract(
        project: Project,
        androidSourceSets: List<AndroidSourceSet>,
    ): CrashlyticsData {

        val googleServicesJson = findGoogleServicesJson(
            variants = variantDataSource.getMigratableBuildVariants(project),
            project = project
        )

        val buildId = grazelExtension.rules.googleServices.crashlytics.buildId

        val extension = project.extensions.getByType<BaseExtension>()
        val packageName = androidManifestParser.parsePackageName(
            extension,
            androidSourceSets,
        )

        return CrashlyticsData(
            packageName = packageName,
            buildId = buildId,
            googleServicesJson = googleServicesJson,
        )
    }
}
