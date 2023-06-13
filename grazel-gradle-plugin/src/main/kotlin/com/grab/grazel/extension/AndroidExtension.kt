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

package com.grab.grazel.extension

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
 * @param incrementalDexing Controls `android_binary`s` incremental dexing flag
 * @param features Enable/disable migration features. See [AndroidFeatures]
 */
data class AndroidExtension(
    var multiDexEnabled: Boolean = true,
    var dexShards: Int? = null,
    var variantFilter: Action<VariantFilter>? = null,
    var incrementalDexing: Boolean = false,
    var features: AndroidFeatures = DefaultAndroidFeatures(),
    var ndkApiLevel: Int? = null
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
     * Generate a `.bazelrc` compatible metadata file containing metadata about which maven dependencies
     * use databinding.
     * Example:
     * ```
     * build --android_databinding_package_info=com_grab_grazel=com.grab.grazel
     * ```
     *
     * Needed due to https://github.com/bazelbuild/bazel/issues/13640
     *
     * Note: This requires a patch on bazel to ensure the flag is read correctly
     */
    var dataBindingMetaData: Boolean
}

data class DefaultAndroidFeatures(
    override var dataBindingMetaData: Boolean = false
) : AndroidFeatures

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