/*
 * Copyright 2021 Grabtaxi Holdings PTE LTE (GRAB)
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

package com.grab.grazel.configuration

import groovy.lang.Closure

data class GoogleServicesConfiguration(
    val crashlytics: CrashlyticsConfiguration = CrashlyticsConfiguration()
) {
    fun crashlytics(block: CrashlyticsConfiguration.() -> Unit) {
        block(crashlytics)
    }

    fun crashlytics(closure: Closure<*>) {
        closure.delegate = crashlytics
        closure.call()
    }
}

data class CrashlyticsConfiguration(
    var buildId: String = "042cb4d8-56f8-41a0-916a-9da28e94d1bc" // Default build id
)