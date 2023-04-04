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
import com.grab.grazel.migrate.TargetBuilder
import com.grab.grazel.migrate.android.AndroidLibraryDataExtractor
import com.grab.grazel.migrate.android.AndroidManifestParser
import com.grab.grazel.migrate.android.AndroidUnitTestDataExtractor
import com.grab.grazel.migrate.android.DefaultAndroidLibraryDataExtractor
import com.grab.grazel.migrate.android.DefaultAndroidManifestParser
import com.grab.grazel.migrate.android.DefaultAndroidUnitTestDataExtractor
import com.grab.grazel.migrate.android.toBuildConfigTarget
import com.grab.grazel.migrate.android.toKtLibraryTarget
import com.grab.grazel.migrate.android.toUnitTestTarget
import com.grab.grazel.migrate.toBazelDependency
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import org.gradle.api.Project
import javax.inject.Inject
import javax.inject.Singleton

@Module
internal interface KtAndroidLibTargetBuilderModule {
    @Binds
    fun DefaultAndroidManifestParser.bindAndroidManifestParser(): AndroidManifestParser

    @Binds
    fun DefaultAndroidLibraryDataExtractor.bindAndroidLibraryDataExtractor(): AndroidLibraryDataExtractor

    @Binds
    fun DefaultAndroidUnitTestDataExtractor.bindAndroidUnitTestDataExtractor(): AndroidUnitTestDataExtractor

    @Binds
    @IntoSet
    fun KtAndroidLibTargetBuilder.bindKtLibTargetBuilder(): TargetBuilder
}


@Singleton
internal class KtAndroidLibTargetBuilder @Inject constructor(
    private val androidLibraryDataExtractor: AndroidLibraryDataExtractor,
    private val unitTestDataExtractor: AndroidUnitTestDataExtractor,
    private val testExtension: TestExtension,
    private val variantMatcher: VariantMatcher,
) : TargetBuilder {

    override fun build(project: Project) = buildList {
        variantMatcher.matchedVariants(project, ConfigurationScope.BUILD)
            .forEach { matchedVariant ->
                val androidLibraryData = androidLibraryDataExtractor.extract(
                    project = project,
                    matchedVariant = matchedVariant
                ).run {
                    val deps = deps.toMutableList()
                    toBuildConfigTarget().let { target ->
                        deps.add(target.toBazelDependency())
                        add(target)
                    }
                    copy(deps = deps)
                }

                androidLibraryData.toKtLibraryTarget()?.let(::add)
            }

        if (testExtension.enableTestMigration) {
            variantMatcher.matchedVariants(project, ConfigurationScope.TEST)
                .forEach { variant ->
                    add(unitTestDataExtractor.extract(project, variant).toUnitTestTarget())
                }
        }
    }

    override fun canHandle(project: Project): Boolean = with(project) {
        isAndroid && isKotlin && !isAndroidApplication && false
    }
}