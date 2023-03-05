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

import com.grab.grazel.GrazelExtension
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.gradle.variant.nameSuffix
import org.gradle.api.Project
import javax.inject.Inject
import javax.inject.Singleton

internal data class CrashlyticsData(
    val name: String,
    val packageName: String?,
    val buildId: String?,
    val googleServicesJson: String?,
)

internal interface CrashlyticsDataExtractor {
    fun extract(
        project: Project,
        matchedVariant: MatchedVariant
    ): CrashlyticsData
}

@Singleton
internal class DefaultCrashlyticsDataExtractor
@Inject
constructor(
    private val grazelExtension: GrazelExtension,
    private val googleServicesJsonExtractor: GoogleServicesJsonExtractor
) : CrashlyticsDataExtractor {

    override fun extract(
        project: Project,
        matchedVariant: MatchedVariant,
    ): CrashlyticsData {
        val googleServicesJson = googleServicesJsonExtractor.extract(project, matchedVariant)
        val buildId = grazelExtension.rules.googleServices.crashlytics.buildId
        return CrashlyticsData(
            name = "crashlytics${matchedVariant.nameSuffix}",
            packageName = matchedVariant.variant.applicationId,
            buildId = buildId,
            googleServicesJson = googleServicesJson,
        )
    }
}

internal fun CrashlyticsData.toTarget() = CrashlyticsTarget(
    name = name,
    packageName = packageName,
    buildId = buildId,
    googleServicesJson = googleServicesJson,
)
