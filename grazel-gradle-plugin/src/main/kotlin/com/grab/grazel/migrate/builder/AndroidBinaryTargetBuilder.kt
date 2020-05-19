/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate.builder

import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.gradle.isKotlin
import com.grab.grazel.migrate.BazelTarget
import com.grab.grazel.migrate.TargetBuilder
import com.grab.grazel.migrate.android.AndroidBinaryDataExtractor
import com.grab.grazel.migrate.android.AndroidBinaryTarget
import com.grab.grazel.migrate.android.AndroidLibraryDataExtractor
import com.grab.grazel.migrate.android.DefaultAndroidBinaryDataExtractor
import com.grab.grazel.migrate.android.DefaultKeyStoreExtractor
import com.grab.grazel.migrate.android.DefaultManifestValuesBuilder
import com.grab.grazel.migrate.android.KeyStoreExtractor
import com.grab.grazel.migrate.android.ManifestValuesBuilder
import com.grab.grazel.migrate.android.SourceSetType
import com.grab.grazel.migrate.toBazelDependency
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import org.gradle.api.Project
import javax.inject.Inject
import javax.inject.Singleton


@Module
internal interface AndroidBinaryTargetBuilderModule {
    @Binds
    fun DefaultAndroidBinaryDataExtractor.bindAndroidBinaryDataExtractor(): AndroidBinaryDataExtractor

    @Binds
    fun DefaultKeyStoreExtractor.bindKeyStoreExtractor(): KeyStoreExtractor

    @Binds
    fun DefaultManifestValuesBuilder.bindDefaultManifestValuesBuilder(): ManifestValuesBuilder

    @Binds
    @IntoSet
    fun AndroidBinaryTargetBuilder.bindAndroidBinaryTargetBuilder(): TargetBuilder
}

@Singleton
internal class AndroidBinaryTargetBuilder @Inject constructor(
    private val androidLibDataExtractor: AndroidLibraryDataExtractor,
    private val androidBinDataExtractor: AndroidBinaryDataExtractor
) : TargetBuilder {

    override fun build(project: Project): List<BazelTarget> {
        val ktAndroidTargets = buildKtAndroidTargets(project)
        return buildAndroidBinaryTargets(project, ktAndroidTargets)
    }

    private fun buildAndroidBinaryTargets(project: Project, intermediateTargets: List<BazelTarget>): List<BazelTarget> {
        var androidLibData = androidLibDataExtractor.extract(project)
        val deps = if (project.isKotlin) {
            // For kotlin project, don't duplicate Maven dependencies
            intermediateTargets.map { it.toBazelDependency() }
        } else {
            intermediateTargets.map { it.toBazelDependency() } + androidLibData.deps
        }

        androidLibData = androidLibData.copy(deps = deps)
        val binaryData = androidBinDataExtractor.extract(project, androidLibData)

        return intermediateTargets + AndroidBinaryTarget(
            name = binaryData.name,
            deps = androidLibData.deps + binaryData.deps,
            srcs = androidLibData.srcs,
            multidex = binaryData.multidex,
            debugKey = binaryData.debugKey,
            dexShards = binaryData.dexShards,
            enableDataBinding = binaryData.hasDatabinding,
            packageName = androidLibData.packageName,
            manifest = androidLibData.manifestFile,
            manifestValues = binaryData.manifestValues,
            res = androidLibData.res,
            extraRes = androidLibData.extraRes,
            assetsGlob = androidLibData.assets,
            assetsDir = androidLibData.assetsDir,
            buildId = binaryData.buildId,
            googleServicesJson = binaryData.googleServicesJson,
            hasCrashlytics = binaryData.hasCrashlytics
        )
    }

    private fun buildKtAndroidTargets(project: Project): List<BazelTarget> {
        return mutableListOf<BazelTarget>().apply {
            val androidProjectData = androidLibDataExtractor.extract(
                project = project,
                sourceSetType = SourceSetType.JAVA_KOTLIN
            ).copy(name = "${project.name}_lib", hasDatabinding = false)
            var deps = androidProjectData.deps
            with(androidProjectData) {
                toBuildConfigTarget().also {
                    deps += it.toBazelDependency()
                    add(it)
                }
                toResValueTarget()?.also {
                    deps += it.toBazelDependency()
                    add(it)
                }
            }

            androidProjectData
                .copy(deps = deps)
                .toKtLibraryTarget()
                ?.also { add(it) }
        }
    }

    override fun canHandle(project: Project): Boolean = project.isAndroidApplication
}
