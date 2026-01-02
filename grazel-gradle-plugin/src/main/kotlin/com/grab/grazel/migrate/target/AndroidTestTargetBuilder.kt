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

package com.grab.grazel.migrate.target

import com.grab.grazel.bazel.rules.Visibility
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.gradle.isAndroidTest
import com.grab.grazel.gradle.variant.VariantMatcher
import com.grab.grazel.migrate.TargetBuilder
import com.grab.grazel.migrate.android.AndroidBinaryDataExtractor
import com.grab.grazel.migrate.android.AndroidLibraryDataExtractor
import com.grab.grazel.migrate.android.AndroidTestData
import com.grab.grazel.migrate.android.AndroidTestDataExtractor
import com.grab.grazel.migrate.android.AndroidTestTarget
import com.grab.grazel.migrate.android.DefaultAndroidTestDataExtractor
import com.grab.grazel.migrate.android.DefaultTargetProjectResolver
import com.grab.grazel.migrate.android.TargetProjectResolver
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import org.gradle.api.Project
import javax.inject.Inject
import javax.inject.Singleton

@Module
internal interface AndroidTestTargetBuilderModule {

    @Binds
    fun bindAndroidTestDataExtractor(
        extractor: DefaultAndroidTestDataExtractor
    ): AndroidTestDataExtractor

    @Binds
    fun bindTargetProjectResolver(resolver: DefaultTargetProjectResolver): TargetProjectResolver

    @Binds
    @IntoSet
    fun bindAndroidTestTargetBuilder(builder: AndroidTestTargetBuilder): TargetBuilder
}

@Singleton
internal class AndroidTestTargetBuilder
@Inject constructor(
    private val androidLibraryDataExtractor: AndroidLibraryDataExtractor,
    private val androidBinaryDataExtractor: AndroidBinaryDataExtractor,
    private val androidTestDataExtractor: AndroidTestDataExtractor,
    private val variantMatcher: VariantMatcher,
) : TargetBuilder {

    override fun build(project: Project) = buildList {
        // Get variants from the TEST MODULE itself
        variantMatcher.matchedVariants(
            project,
            VariantType.AndroidBuild
        ).forEach { matchedVariant ->
            // Extract common library fields (srcs, resourceSets, etc.)
            val androidLibraryData = androidLibraryDataExtractor.extract(
                project = project,
                matchedVariant = matchedVariant
            )

            // Extract binary-specific fields (manifestValues, debugKey, etc.)
            val androidBinaryData = androidBinaryDataExtractor.extract(
                project = project,
                matchedVariant = matchedVariant
            )

            // Extract test-specific fields (associates, instruments, etc.)
            val androidTestData = androidTestDataExtractor.extract(
                project = project,
                matchedVariant = matchedVariant,
                androidLibraryData = androidLibraryData,
                androidBinaryData = androidBinaryData
            )

            add(androidTestData.toTarget())
        }
    }

    override fun canHandle(project: Project): Boolean = project.isAndroidTest
}

internal fun AndroidTestData.toTarget() = AndroidTestTarget(
    name = name,
    srcs = srcs,
    deps = deps,
    tags = tags,
    visibility = Visibility.Public,
    enableDataBinding = databinding,
    enableCompose = compose,
    projectName = name,
    resourceSets = resourceSets,
    resValuesData = resValuesData,
    buildConfigData = buildConfigData,
    packageName = packageName,
    manifest = manifestFile,
    assetsGlob = assets,
    assetsDir = if (assets.isNotEmpty()) resourceStripPrefix else null,
    lintConfigData = lintConfigData,
    // Test-specific fields
    associates = associates,
    instruments = instruments,
    customPackage = customPackage,
    targetPackage = targetPackage,
    testInstrumentationRunner = testInstrumentationRunner,
    manifestValues = manifestValues,
    debugKey = debugKey,
    resources = resources,
    resourceFiles = resourceFiles,
    resourceStripPrefix = resourceStripPrefix,
)