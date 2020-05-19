/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.configuration

import com.android.build.gradle.api.BaseVariant
import com.android.builder.model.BuildType
import com.android.builder.model.ProductFlavor
import groovy.lang.Closure
import org.gradle.api.Action

/**
 * Configuration for Android binary target.
 *
 * @param multiDexEnabled Whether generated android_binary rules will have multidex enabled.
 * @param dexShards The dex_shards attribute to set in android_binary rule
 * @param variantFilter Variant filter instance configured by the user to filter android variants
 * @param features Enable/disable migration features. See [AndroidFeatures]
 */
data class AndroidConfiguration(
    var multiDexEnabled: Boolean = true,
    var dexShards: Int? = null,
    var variantFilter: Action<VariantFilter>? = null,
    var features: AndroidFeatures = DefaultAndroidFeatures()
) {
    fun variantFilter(action: Action<VariantFilter>) {
        variantFilter = action
    }

    fun features(block: AndroidFeatures.() -> Unit) {
        block(features)
    }

    fun features(closure: Closure<*>) {
        closure.delegate = features
        closure.call()
    }
}

/**
 * Defines migration features that Grazel will use as part of migration. Mostly used to enable/disable features.
 */
interface AndroidFeatures {
    /**
     * Migrate modules using dataBinding during migration. Enabling this will use custom databinding macro provided from
     * Bazel common project.
     *
     * Default `false`
     */
    var dataBinding: Boolean
}

data class DefaultAndroidFeatures(override var dataBinding: Boolean = false) : AndroidFeatures

interface VariantFilter {
    fun setIgnore(ignore: Boolean)
    val buildType: BuildType
    val flavors: List<ProductFlavor>
    val name: String
}

internal class DefaultVariantFilter(variant: BaseVariant) : VariantFilter {
    var ignored: Boolean = false
    override fun setIgnore(ignore: Boolean) {
        ignored = ignore
    }

    override val buildType: BuildType = variant.buildType
    override val flavors: List<ProductFlavor> = variant.productFlavors
    override val name: String = variant.name
}