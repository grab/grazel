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

package com.grab.grazel.migrate.builder

import com.grab.grazel.configuration.KotlinConfiguration
import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.isKotlin
import com.grab.grazel.migrate.BazelTarget
import com.grab.grazel.migrate.TargetBuilder
import com.grab.grazel.migrate.kotlin.DefaultKotlinProjectDataExtractor
import com.grab.grazel.migrate.kotlin.KotlinProjectData
import com.grab.grazel.migrate.kotlin.KotlinProjectDataExtractor
import com.grab.grazel.migrate.kotlin.KtLibraryTarget
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import org.gradle.api.Project
import javax.inject.Inject
import javax.inject.Singleton

@Module
internal interface KtLibTargetBuilderModule {
    @Binds
    fun DefaultKotlinProjectDataExtractor.bindKotlinProjectDataExtractor(): KotlinProjectDataExtractor

    @Binds
    @IntoSet
    fun KtLibTargetBuilder.bindKtLibTargetBuilder(): TargetBuilder

}


@Singleton
internal class KtLibTargetBuilder @Inject constructor(
    private val projectDataExtractor: KotlinProjectDataExtractor,
    private val kotlinConfiguration: KotlinConfiguration
) : TargetBuilder {
    override fun build(project: Project): List<BazelTarget> {
        val projectData = projectDataExtractor.extract(project)
        if (projectData.srcs.isEmpty()) return emptyList()
        return listOf(projectData.toKtLibraryTarget(kotlinConfiguration.enabledTransitiveReduction))
    }

    override fun canHandle(project: Project): Boolean = with(project) {
        !isAndroid && isKotlin
    }
}

private fun KotlinProjectData.toKtLibraryTarget(enabledTransitiveDepsReduction: Boolean = false): KtLibraryTarget {
    return KtLibraryTarget(
        name = name,
        srcs = srcs,
        res = res,
        deps = deps,
        tags = if (enabledTransitiveDepsReduction) {
            deps.toDirectTranDepTags(self = name)
        } else emptyList()
    )
}

