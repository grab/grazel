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

import com.grab.grazel.gradle.ConfigurationScope.ANDROID_TEST
import com.grab.grazel.gradle.hasTestInstrumentationRunner
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.gradle.variant.VariantMatcher
import com.grab.grazel.migrate.TargetBuilder
import com.grab.grazel.migrate.android.AndroidInstrumentationBinaryData
import com.grab.grazel.migrate.android.AndroidInstrumentationBinaryDataExtractor
import com.grab.grazel.migrate.android.AndroidInstrumentationBinaryTarget
import com.grab.grazel.migrate.android.DefaultAndroidInstrumentationBinaryDataExtractor
import com.grab.grazel.migrate.android.SourceSetType
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import org.gradle.api.Project
import javax.inject.Inject
import javax.inject.Singleton

@Module
internal interface AndroidInstrumentationBinaryTargetBuilderModule {

    @Binds
    fun DefaultAndroidInstrumentationBinaryDataExtractor.bindAndroidInstrumentationBinaryDataExtractor(): AndroidInstrumentationBinaryDataExtractor

    @Binds
    @IntoSet
    fun AndroidInstrumentationBinaryTargetBuilder.bindAndroidInstrumentationBinaryTargetBuilder(): TargetBuilder
}

@Singleton
internal class AndroidInstrumentationBinaryTargetBuilder
@Inject constructor(
    private val androidInstrumentationBinDataExtractor: AndroidInstrumentationBinaryDataExtractor,
    private val variantMatcher: VariantMatcher,
) : TargetBuilder {

    override fun build(project: Project) = buildList {
        variantMatcher.matchedVariants(
            project,
            ANDROID_TEST
        ).forEach { matchedVariant ->
            val androidInstrumentationBinData = androidInstrumentationBinDataExtractor.extract(
                project = project,
                matchedVariant = matchedVariant,
                sourceSetType = SourceSetType.JAVA_KOTLIN,
            )
            add(androidInstrumentationBinData.toTarget())
        }
    }

    override fun canHandle(project: Project): Boolean = project.isAndroidApplication
        && project.hasTestInstrumentationRunner

    override fun sortOrder(): Int = 3

    private fun AndroidInstrumentationBinaryData.toTarget() = AndroidInstrumentationBinaryTarget(
        name = name,
        associates = associates,
        customPackage = customPackage,
        targetPackage = targetPackage,
        debugKey = debugKey,
        deps = deps,
        instruments = instruments,
        manifestValues = manifestValues,
        resources = resources,
        resourceStripPrefix = resourceStripPrefix,
        resourceFiles = resourceFiles,
        srcs = srcs,
        testInstrumentationRunner = testInstrumentationRunner,
    )
}


