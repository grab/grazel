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

package com.grab.grazel.migrate.builder

import com.android.build.gradle.api.AndroidSourceSet
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.hasCrashlytics
import com.grab.grazel.gradle.hasGooglePlayServicesPlugin
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.migrate.BazelTarget
import com.grab.grazel.migrate.TargetBuilder
import com.grab.grazel.migrate.android.CrashlyticsData
import com.grab.grazel.migrate.android.CrashlyticsDataExtractor
import com.grab.grazel.migrate.android.CrashlyticsTarget
import com.grab.grazel.migrate.android.DefaultCrashlyticsDataExtractor
import com.grab.grazel.migrate.android.VariantsMerger
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import org.gradle.api.Project
import javax.inject.Inject

@Module
internal interface CrashlyticsTargetBuilderModule {

    @Binds
    fun DefaultCrashlyticsDataExtractor.bindCrashlyticsDataExtractor(): CrashlyticsDataExtractor

    @Binds
    @IntoSet
    fun CrashlyticsTargetBuilder.bindCrashlyticsTargetBuilder(): TargetBuilder
}

internal class CrashlyticsTargetBuilder @Inject constructor(
    private val crashlyticsDataExtractor: CrashlyticsDataExtractor,
    private val variantsMerger: VariantsMerger,
) : TargetBuilder {

    override fun build(project: Project): List<BazelTarget> =
        listOf(
            project.buildCrashlyticsTarget()
        )

    override fun canHandle(project: Project): Boolean =
        project.isAndroidApplication &&
            project.hasGooglePlayServicesPlugin &&
            project.hasCrashlytics

    override fun sortOrder(): Int = 0

    private fun Project.buildCrashlyticsTarget(): BazelTarget =
        variantsMerger
            .merge(project, ConfigurationScope.BUILD)
            .map { mergedVariant ->
                mergedVariant.variant.sourceSets
                    .filterIsInstance<AndroidSourceSet>()
            }
            .map { migratableSourceSets ->
                crashlyticsDataExtractor.extract(
                    project = project,
                    androidSourceSets = migratableSourceSets,
                )
            }
            .map { it.toTarget() }
            .first()

    private fun CrashlyticsData.toTarget(): CrashlyticsTarget =
        CrashlyticsTarget(
            packageName = packageName,
            buildId = buildId,
            googleServicesJson = googleServicesJson,
        )
}
