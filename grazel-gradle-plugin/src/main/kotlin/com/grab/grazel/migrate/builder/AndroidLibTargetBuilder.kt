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

import com.grab.grazel.extension.TestExtension
import com.grab.grazel.gradle.ConfigurationScope
import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.gradle.isKotlin
import com.grab.grazel.gradle.variant.VariantMatcher
import com.grab.grazel.migrate.BazelTarget
import com.grab.grazel.migrate.TargetBuilder
import com.grab.grazel.migrate.android.AndroidLibraryData
import com.grab.grazel.migrate.android.AndroidLibraryDataExtractor
import com.grab.grazel.migrate.android.AndroidLibraryTarget
import com.grab.grazel.migrate.android.AndroidUnitTestDataExtractor
import com.grab.grazel.migrate.android.toUnitTestTarget
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import org.gradle.api.Project
import javax.inject.Inject
import javax.inject.Singleton

@Module
internal interface AndroidLibTargetBuilderModule {
    @Binds
    @IntoSet
    fun AndroidLibTargetBuilder.bindKtLibTargetBuilder(): TargetBuilder
}

@Singleton
internal class AndroidLibTargetBuilder @Inject constructor(
    private val androidLibraryDataExtractor: AndroidLibraryDataExtractor,
    private val unitTestDataExtractor: AndroidUnitTestDataExtractor,
    private val testExtension: TestExtension,
    private val variantMatcher: VariantMatcher,
) : TargetBuilder {

    override fun build(project: Project): List<BazelTarget> {
        return variantMatcher.matchedVariants(project, ConfigurationScope.BUILD)
            .map { matchedVariant ->
                androidLibraryDataExtractor
                    .extract(project, matchedVariant)
                    .toAndroidLibTarget()
            } + unitTestsTargets(project)
    }

    private fun unitTestsTargets(project: Project) =
        if (testExtension.enableTestMigration) {
            variantMatcher.matchedVariants(
                project,
                ConfigurationScope.TEST
            ).map { matchedVariant ->
                unitTestDataExtractor.extract(project, matchedVariant).toUnitTestTarget()
            }
        } else {
            emptyList()
        }

    override fun canHandle(project: Project): Boolean = with(project) {
        isAndroid && !isKotlin && !isAndroidApplication
    }

    override fun sortOrder(): Int = 2
}

private fun AndroidLibraryData.toAndroidLibTarget() = AndroidLibraryTarget(
    name = name,
    srcs = srcs,
    deps = deps,
    enableDataBinding = hasDatabinding,
    res = res,
    resValues = resValues,
    customResourceSets = extraRes,
    packageName = packageName,
    manifest = manifestFile,
    assetsGlob = assets,
    assetsDir = assetsDir
)

