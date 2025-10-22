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

import com.grab.grazel.gradle.ConfigurationScope.BUILD
import com.grab.grazel.gradle.isAndroidTest
import com.grab.grazel.gradle.variant.VariantMatcher
import com.grab.grazel.migrate.TargetBuilder
import com.grab.grazel.migrate.android.AndroidTestData
import com.grab.grazel.migrate.android.AndroidTestDataExtractor
import com.grab.grazel.migrate.android.DefaultAndroidTestDataExtractor
import com.grab.grazel.migrate.android.DefaultTargetProjectResolver
import com.grab.grazel.migrate.android.TargetProjectResolver
import com.grab.grazel.migrate.android.toTarget
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import org.gradle.api.Project
import javax.inject.Inject
import javax.inject.Singleton

@Module
internal interface AndroidTestTargetBuilderModule {

    @Binds
    fun DefaultAndroidTestDataExtractor.bindAndroidTestDataExtractor(): AndroidTestDataExtractor

    @Binds
    fun DefaultTargetProjectResolver.bindTargetProjectResolver(): TargetProjectResolver

    @Binds
    @IntoSet
    fun AndroidTestTargetBuilder.bindAndroidTestTargetBuilder(): TargetBuilder
}

/**
 * TargetBuilder implementation for creating AndroidTestTarget instances from com.android.test modules.
 *
 * This builder is responsible for:
 * - Identifying com.android.test projects
 * - Extracting test data for matched variants
 * - Converting the data to Bazel build targets
 */
@Singleton
internal class AndroidTestTargetBuilder
@Inject constructor(
    private val androidTestDataExtractor: AndroidTestDataExtractor,
    private val variantMatcher: VariantMatcher,
) : TargetBuilder {

    override fun build(project: Project) = buildList {
        variantMatcher.matchedVariants(
            project,
            BUILD
        ).forEach { matchedVariant ->
            val androidTestData = androidTestDataExtractor.extract(
                project = project,
                matchedVariant = matchedVariant,
            )
            add(androidTestData.toTarget())
        }
    }

    override fun canHandle(project: Project): Boolean = project.isAndroidTest
}
