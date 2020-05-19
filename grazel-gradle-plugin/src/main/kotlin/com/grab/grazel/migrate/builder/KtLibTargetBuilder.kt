/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
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

