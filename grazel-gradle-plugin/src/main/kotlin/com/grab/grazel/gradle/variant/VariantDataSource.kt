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

package com.grab.grazel.gradle.variant

import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.TestVariant
import com.android.build.gradle.api.UnitTestVariant
import com.android.builder.model.ProductFlavor
import com.grab.grazel.extension.DefaultVariantFilter
import com.grab.grazel.extension.VariantFilter
import com.grab.grazel.gradle.ConfigurationScope
import org.gradle.api.Action
import org.gradle.api.Project


internal interface AndroidVariantDataSource {
    /**
     * Variant filter instance to filter out unsupported variants
     */
    val variantFilter: Action<VariantFilter>?

    /**
     * This method will return the flavors which are ignored after evaluate the ignore variants
     * determined by [variantFilter]
     */
    fun getIgnoredFlavors(project: Project): List<ProductFlavor>

    /**
     * This method will return the variants which are ignored by the configuration determined by [variantFilter]
     */
    fun getIgnoredVariants(project: Project): List<BaseVariant>

    /**
     * @return The list of variants that can be migrated.
     */
    fun getMigratableVariants(project: Project): List<BaseVariant>

    /**
     * @return all variants minus the ones that declared in filtered variants
     */
    fun getMigratableVariants(
        project: Project,
        configurationScope: ConfigurationScope?
    ): Set<BaseVariant>

    fun buildTypeFallbacks(project: Project): Map<String, Set<String>>

    fun flavorFallbacks(project: Project): Map<String, Set<String>>
}

internal class DefaultAndroidVariantDataSource(
    private val androidVariantsExtractor: AndroidVariantsExtractor,
    override val variantFilter: Action<VariantFilter>? = null,
) : AndroidVariantDataSource {

    private fun Project.androidVariants() =
        androidVariantsExtractor.getVariants(this) +
            androidVariantsExtractor.getUnitTestVariants(this) +
            androidVariantsExtractor.getTestVariants(this)

    override fun getMigratableVariants(
        project: Project,
        configurationScope: ConfigurationScope?
    ): Set<BaseVariant> {
        return when (configurationScope) {
            ConfigurationScope.TEST -> androidVariantsExtractor.getUnitTestVariants(project)
            ConfigurationScope.ANDROID_TEST -> androidVariantsExtractor.getTestVariants(project)
            else -> androidVariantsExtractor.getVariants(project)
        }.filterNot(::ignoredVariantFilter).toSet()
    }

    override fun getIgnoredFlavors(project: Project): List<ProductFlavor> {
        val supportedFlavors = getMigratableVariants(project)
            .flatMap(BaseVariant::getProductFlavors)
            .map { it.name }
            .distinct()
        return androidVariantsExtractor.getFlavors(project)
            .filter { flavor -> !supportedFlavors.any { it == flavor.name } }
    }

    override fun getIgnoredVariants(project: Project): List<BaseVariant> {
        return project.androidVariants().filter(::ignoredVariantFilter)
    }

    override fun getMigratableVariants(project: Project): List<BaseVariant> {
        return project.androidVariants().filterNot(::ignoredVariantFilter)
    }

    override fun buildTypeFallbacks(project: Project): Map<String, Set<String>> {
        return androidVariantsExtractor.getBuildTypes(project)
            .groupBy { it.name }
            .mapValues { (_, buildTypes) ->
                buildTypes
                    .filterIsInstance<com.android.build.gradle.internal.dsl.BuildType>()
                    .map { it.matchingFallbacks }
                    .flatten()
                    .toSet()
            }
    }

    override fun flavorFallbacks(project: Project): Map<String, Set<String>> {
        return androidVariantsExtractor.getFlavors(project)
            .groupBy { it.name }
            .mapValues { (_, flavors) ->
                flavors
                    .filterIsInstance<com.android.build.gradle.internal.dsl.ProductFlavor>()
                    .map { it.matchingFallbacks }
                    .flatten()
                    .toSet()
            }
    }

    private fun ignoredVariantFilter(
        variant: BaseVariant
    ): Boolean = DefaultVariantFilter(variant)
        .apply { variantFilter?.execute(this) }
        .ignored
}

internal fun AndroidVariantDataSource.getMigratableBuildVariants(project: Project): List<BaseVariant> =
    getMigratableVariants(project)
        .filter { it !is UnitTestVariant && it !is TestVariant }