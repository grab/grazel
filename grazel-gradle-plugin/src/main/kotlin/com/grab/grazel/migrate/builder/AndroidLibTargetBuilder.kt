/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate.builder

import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.gradle.isKotlin
import com.grab.grazel.migrate.BazelTarget
import com.grab.grazel.migrate.TargetBuilder
import com.grab.grazel.migrate.android.AndroidLibraryData
import com.grab.grazel.migrate.android.AndroidLibraryDataExtractor
import com.grab.grazel.migrate.android.AndroidLibraryTarget
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
    private val projectDataExtractor: AndroidLibraryDataExtractor
) : TargetBuilder {

    override fun build(project: Project): List<BazelTarget> {
        return listOf(projectDataExtractor.extract(project).toAndroidLibTarget())
    }


    override fun canHandle(project: Project): Boolean = with(project) {
        isAndroid && !isKotlin && !isAndroidApplication
    }
}

private fun AndroidLibraryData.toAndroidLibTarget() = AndroidLibraryTarget(
    name = name,
    enableDataBinding = hasDatabinding,
    packageName = packageName,
    srcs = srcs,
    manifest = manifestFile,
    res = res,
    extraRes = extraRes,
    deps = deps,
    assetsGlob = assets,
    assetsDir = assetsDir
)

