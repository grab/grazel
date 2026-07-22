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

import com.grab.grazel.gradle.isAndroid
import com.grab.grazel.gradle.isAndroidTest
import com.grab.grazel.gradle.isKotlin
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.WorkspaceRenderPlanService
import com.grab.grazel.migrate.BazelTarget
import com.grab.grazel.migrate.TargetBuilder
import com.grab.grazel.migrate.kotlin.DefaultKotlinProjectDataExtractor
import com.grab.grazel.migrate.kotlin.DefaultKotlinUnitTestDataExtractor
import com.grab.grazel.migrate.kotlin.KotlinLibraryTarget
import com.grab.grazel.migrate.kotlin.KotlinProjectData
import com.grab.grazel.migrate.kotlin.KotlinProjectDataExtractor
import com.grab.grazel.migrate.kotlin.KotlinUnitTestDataExtractor
import com.grab.grazel.migrate.kotlin.toUnitTestTarget
import com.grab.grazel.util.GradleProvider
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
    private val dependencyResolutionService: GradleProvider<DefaultDependencyResolutionService>,
    private val workspaceRenderPlanService: GradleProvider<WorkspaceRenderPlanService>,
) : TargetBuilder {

    override fun build(project: Project): List<BazelTarget> =
        selectKotlinData(project).map { selected ->
            when (selected) {
                is KotlinLibraryTargetData -> selected.data.toKotlinLibraryTarget()
                is KotlinUnitTestTargetData -> selected.data.toUnitTestTarget()
                else -> error("Unexpected TargetData for Kotlin project: $selected")
            }
        }

    override fun selectData(project: Project): List<TargetData> = selectKotlinData(project)

    /**
     * A Kotlin project contributes its library + unit-test data only when reachable (or
     * already referenced) — the same three-signal gate documented on [isReachableJvmProject].
     */
    internal fun selectKotlinData(project: Project): List<TargetData> {
        if (!isReachableJvmProject(project, dependencyResolutionService, workspaceRenderPlanService)) {
            return emptyList()
        }
        return listOf(
            KotlinLibraryTargetData(projectDataExtractor.extract(project)),
            KotlinUnitTestTargetData(kotlinUnitTestDataExtractor.extract(project))
        )
    }

    override fun canHandle(project: Project): Boolean = with(project) {
        !isAndroid && !isAndroidTest && isKotlin
    }

    private fun KotlinProjectData.toKotlinLibraryTarget() = KotlinLibraryTarget(
        name = name,
        srcs = srcs,
        res = res,
        deps = deps,
        tags = tags,
        lintConfigData = lintConfigData,
        plugins = plugins
    )
}
