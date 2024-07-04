/*
 * Copyright 2024 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.build.android

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.DynamicFeatureAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.TestAndroidComponentsExtension
import com.android.build.api.variant.Variant
import com.android.build.api.variant.VariantBuilder
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LintPlugin
import com.grab.grazel.build.gradle.configureIfExist
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import com.android.build.gradle.BasePlugin as AndroidBasePlugin

internal fun Project.android(builder: BaseExtension.() -> Unit) {
    configure(builder)
}

internal fun Project.androidComponents(
    builder: AndroidComponentsExtension<*, out VariantBuilder, out Variant>.() -> Unit
) {
    configureIfExist<LibraryAndroidComponentsExtension>(builder)
    configureIfExist<ApplicationAndroidComponentsExtension>(builder)
    configureIfExist<TestAndroidComponentsExtension>(builder)
    configureIfExist<DynamicFeatureAndroidComponentsExtension>(builder)
}

internal fun Project.configureAndroid() {
    require(this == rootProject) { "Should be only called from root project" }
    subprojects {
        plugins.withType<AndroidBasePlugin> {
            if (this !is LintPlugin) {
                androidCommon()
            }
        }
    }
}