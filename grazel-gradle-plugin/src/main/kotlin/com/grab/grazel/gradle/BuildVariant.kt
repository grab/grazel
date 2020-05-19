/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.gradle

import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.BaseVariant
import com.android.builder.model.ProductFlavor
import com.grab.grazel.configuration.DefaultVariantFilter
import com.grab.grazel.configuration.VariantFilter
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.kotlin.dsl.the
import javax.inject.Inject
import javax.inject.Singleton

internal interface AndroidBuildVariantDataSource {
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
}

internal class DefaultAndroidBuildVariantDataSource(
    private val androidVariantsExtractor: AndroidVariantsExtractor = DefaultAndroidVariantsExtractor(),
    override val variantFilter: Action<VariantFilter>? = null
) : AndroidBuildVariantDataSource {

    override fun getIgnoredFlavors(project: Project): List<ProductFlavor> {
        val supportFlavors = getMigratableVariants(project).flatMap(BaseVariant::getProductFlavors)
        return androidVariantsExtractor.getFlavors(project)
            .filter { flavor -> !supportFlavors.any { it.name == flavor.name } }
    }

    private fun Project.androidVariants() = androidVariantsExtractor.getVariants(this)

    override fun getIgnoredVariants(project: Project): List<BaseVariant> {
        return project.androidVariants().filter(::ignoredVariantFilter)
    }

    override fun getMigratableVariants(project: Project): List<BaseVariant> {
        return project.androidVariants().filterNot(::ignoredVariantFilter)
    }

    private fun ignoredVariantFilter(
        variant: BaseVariant
    ): Boolean = DefaultVariantFilter(variant)
        .apply { variantFilter?.execute(this) }
        .ignored
}

internal interface AndroidVariantsExtractor {
    fun getVariants(project: Project): Set<BaseVariant>
    fun getFlavors(project: Project): Set<ProductFlavor>
}

@Singleton
internal class DefaultAndroidVariantsExtractor @Inject constructor() : AndroidVariantsExtractor {

    override fun getVariants(project: Project): Set<BaseVariant> {
        return when {
            project.isAndroidApplication -> project.the<AppExtension>().applicationVariants
            project.isAndroidLibrary -> project.the<LibraryExtension>().libraryVariants
            else -> emptySet()
        }
    }

    override fun getFlavors(project: Project): Set<ProductFlavor> {
        return when {
            project.isAndroidApplication -> project.the<AppExtension>().productFlavors
            project.isAndroidLibrary -> project.the<LibraryExtension>().productFlavors
            else -> emptySet()
        }
    }
}