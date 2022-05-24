/*
 * Copyright 2021 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.grab.grazel.bazel.rules.KotlinProjectType
import com.grab.grazel.bazel.rules.Visibility
import com.grab.grazel.extension.KotlinExtension
import com.grab.grazel.extension.TestExtension
import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.isAndroidApplication
import com.grab.grazel.gradle.isKotlin
import com.grab.grazel.migrate.BazelTarget
import com.grab.grazel.migrate.TargetBuilder
import com.grab.grazel.migrate.android.AndroidLibraryData
import com.grab.grazel.migrate.android.AndroidLibraryDataExtractor
import com.grab.grazel.migrate.android.AndroidLibraryTarget
import com.grab.grazel.migrate.android.AndroidManifestParser
import com.grab.grazel.migrate.android.AndroidUnitTestDataExtractor
import com.grab.grazel.migrate.android.BuildConfigTarget
import com.grab.grazel.migrate.android.DefaultAndroidLibraryDataExtractor
import com.grab.grazel.migrate.android.DefaultAndroidManifestParser
import com.grab.grazel.migrate.android.DefaultAndroidUnitTestDataExtractor
import com.grab.grazel.migrate.android.SourceSetType
import com.grab.grazel.migrate.android.toUnitTestTarget
import com.grab.grazel.migrate.kotlin.KtLibraryTarget
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
    private val projectDataExtractor: AndroidLibraryDataExtractor,
    private val unitTestDataExtractor: AndroidUnitTestDataExtractor,
    private val kotlinExtension: KotlinExtension,
    private val testExtension: TestExtension
) : TargetBuilder {

    override fun build(project: Project): List<BazelTarget> {
        return mutableListOf<BazelTarget>().apply {
            val projectData = projectDataExtractor.extract(
                project, sourceSetType = SourceSetType.JAVA_KOTLIN
            )
            var deps = projectData.deps
            with(projectData) {
                toAarResTarget()?.also { add(it) }
                toBuildConfigTarget().also {
                    deps += it.toBazelDependency()
                    add(it)
                }
            }
            projectData
                .copy(deps = deps)
                .toKtLibraryTarget()
                ?.also { add(it) }

            if (testExtension.enableTestMigration)
                add(
                    unitTestDataExtractor
                        .extract(project)
                        .toUnitTestTarget()
                )
        }
    }

    override fun canHandle(project: Project): Boolean = with(project) {
        isAndroid && isKotlin && !isAndroidApplication
    }
}


internal fun AndroidLibraryData.toKtLibraryTarget(): KtLibraryTarget? =
    if (srcs.isNotEmpty() || hasDatabinding) {
        KtLibraryTarget(
            name = name,
            kotlinProjectType = KotlinProjectType.Android(hasDatabinding = hasDatabinding),
            packageName = packageName,
            srcs = srcs,
            manifest = manifestFile,
            res = res,
            resValues = resValues,
            customResourceSets = extraRes,
            deps = deps,
            plugins = plugins,
            assetsGlob = assets,
            assetsDir = assetsDir,
            tags = tags
        )
    } else null

internal fun AndroidLibraryData.toAarResTarget(): AndroidLibraryTarget? {
    return if (res.isNotEmpty() && !hasDatabinding) {
        // For hybrid builds we need separate AAR for resources
        // When it is a pure resource module, keep the res target as the main target
        val targetName = if (srcs.isEmpty()) name else "${name}-res"
        AndroidLibraryTarget(
            name = targetName,
            packageName = packageName,
            manifest = manifestFile,
            projectName = name,
            res = res,
            customResourceSets = extraRes,
            visibility = Visibility.Public,
            deps = deps,
            assetsGlob = assets,
            assetsDir = assetsDir
        )
    } else null
}

internal fun AndroidLibraryData.toBuildConfigTarget(): BuildConfigTarget {
    return BuildConfigTarget(
        name = "$name-build-config",
        packageName = packageName,
        strings = buildConfigData.strings,
        booleans = buildConfigData.booleans,
        ints = buildConfigData.ints,
        longs = buildConfigData.longs
    )
}
