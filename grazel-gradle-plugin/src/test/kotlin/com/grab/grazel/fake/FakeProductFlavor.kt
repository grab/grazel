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

package com.grab.grazel.fake

import com.android.build.gradle.api.BaseVariant
import com.android.builder.model.ApiVersion
import com.android.builder.model.ClassField
import com.android.builder.model.ProductFlavor
import com.android.builder.model.VectorDrawablesOptions
import com.grab.grazel.extension.VariantFilter
import com.grab.grazel.gradle.AndroidVariantDataSource
import com.grab.grazel.gradle.ConfigurationScope
import org.gradle.api.Action
import org.gradle.api.Project
import java.io.File

class FakeAndroidVariantDataSource(
    var ignoreFlavorsName: List<String> = emptyList(),
    var ignoreVariantName: List<Pair<String, String?>> = emptyList(),
    override val variantFilter: Action<VariantFilter>? = null
) : AndroidVariantDataSource {
    override fun getIgnoredFlavors(project: Project): List<ProductFlavor> =
        ignoreFlavorsName.map { FakeProductFlavor(it) }

    override fun getIgnoredVariants(project: Project): List<BaseVariant> =
        ignoreVariantName.map { FakeVariant(it.first, it.second) }

    override fun getMigratableVariants(project: Project): List<BaseVariant> {
        return emptyList()
    }

    override fun getMigratableVariants(
        project: Project,
        configurationScope: ConfigurationScope?
    ): Set<BaseVariant> {
        return emptySet()
    }
}

class FakeProductFlavor(private val name: String) : ProductFlavor {
    override val applicationId: String?
        get() = TODO("Not yet implemented")
    override val applicationIdSuffix: String?
        get() = TODO("Not yet implemented")
    override val buildConfigFields: Map<String, ClassField>
        get() = TODO("Not yet implemented")
    override val consumerProguardFiles: Collection<File>
        get() = TODO("Not yet implemented")
    override val dimension: String?
        get() = TODO("Not yet implemented")
    override val manifestPlaceholders: Map<String, Any>
        get() = TODO("Not yet implemented")
    override val maxSdkVersion: Int?
        get() = TODO("Not yet implemented")
    override val minSdkVersion: ApiVersion?
        get() = TODO("Not yet implemented")
    override val multiDexEnabled: Boolean?
        get() = TODO("Not yet implemented")
    override val multiDexKeepFile: File?
        get() = TODO("Not yet implemented")
    override val multiDexKeepProguard: File?
        get() = TODO("Not yet implemented")
    override val proguardFiles: Collection<File>
        get() = TODO("Not yet implemented")
    override val renderscriptNdkModeEnabled: Boolean?
        get() = TODO("Not yet implemented")
    override val renderscriptSupportModeBlasEnabled: Boolean?
        get() = TODO("Not yet implemented")
    override val renderscriptSupportModeEnabled: Boolean?
        get() = TODO("Not yet implemented")
    override val renderscriptTargetApi: Int?
        get() = TODO("Not yet implemented")
    override val resValues: Map<String, ClassField>
        get() = TODO("Not yet implemented")
    override val resourceConfigurations: Collection<String>
        get() = TODO("Not yet implemented")
    override val targetSdkVersion: ApiVersion?
        get() = TODO("Not yet implemented")
    override val testApplicationId: String?
        get() = TODO("Not yet implemented")
    override val testFunctionalTest: Boolean?
        get() = TODO("Not yet implemented")
    override val testHandleProfiling: Boolean?
        get() = TODO("Not yet implemented")
    override val testInstrumentationRunner: String?
        get() = TODO("Not yet implemented")
    override val testInstrumentationRunnerArguments: Map<String, String>
        get() = TODO("Not yet implemented")
    override val testProguardFiles: Collection<File>
        get() = TODO("Not yet implemented")
    override val vectorDrawables: VectorDrawablesOptions
        get() = TODO("Not yet implemented")
    override val versionCode: Int?
        get() = TODO("Not yet implemented")
    override val versionName: String?
        get() = TODO("Not yet implemented")
    override val versionNameSuffix: String?
        get() = TODO("Not yet implemented")
    override val wearAppUnbundled: Boolean?
        get() = TODO("Not yet implemented")

    override fun getName() = name
}