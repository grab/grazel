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

import com.grab.grazel.extension.TestExtension
import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.isKotlin
import com.grab.grazel.migrate.BazelTarget
import com.grab.grazel.migrate.TargetBuilder
import com.grab.grazel.migrate.kotlin.DefaultKotlinProjectDataExtractor
import com.grab.grazel.migrate.kotlin.DefaultKotlinUnitTestDataExtractor
import com.grab.grazel.migrate.kotlin.KotlinLibraryTarget
import com.grab.grazel.migrate.kotlin.KotlinProjectData
import com.grab.grazel.migrate.kotlin.KotlinProjectDataExtractor
import com.grab.grazel.migrate.kotlin.KotlinUnitTestDataExtractor
import com.grab.grazel.migrate.kotlin.toUnitTestTarget
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import org.gradle.api.Project
import javax.inject.Inject
import javax.inject.Singleton

@Module
internal interface KotlinLibraryTargetBuilderModule {
    @Binds
    fun DefaultKotlinProjectDataExtractor.bindKotlinProjectDataExtractor(): KotlinProjectDataExtractor

    @Binds
    fun DefaultKotlinUnitTestDataExtractor.bindKotlinUnitTestDataExtractor(): KotlinUnitTestDataExtractor

    @Binds
    @IntoSet
    fun KotlinLibraryTargetBuilder.bindKtLibTargetBuilder(): TargetBuilder
}


@Singleton
internal class KotlinLibraryTargetBuilder
@Inject
constructor(
    private val projectDataExtractor: KotlinProjectDataExtractor,
    private val kotlinUnitTestDataExtractor: KotlinUnitTestDataExtractor,
    private val testExtension: TestExtension
) : TargetBuilder {

    override fun build(project: Project): List<BazelTarget> {
        val projectData = projectDataExtractor.extract(project)
        val ktLibTargets = projectData.toKotlinLibraryTarget()
        return if (testExtension.enableTestMigration) {
            val unitTestsTargets = kotlinUnitTestDataExtractor
                .extract(project)
                .toUnitTestTarget()
            listOf(ktLibTargets, unitTestsTargets)
        } else listOf(ktLibTargets)
    }

    override fun canHandle(project: Project): Boolean = with(project) {
        !isAndroid && isKotlin
    }

    private fun KotlinProjectData.toKotlinLibraryTarget() = KotlinLibraryTarget(
        name = name,
        srcs = srcs,
        res = res,
        deps = deps,
        tags = tags,
    )
}

