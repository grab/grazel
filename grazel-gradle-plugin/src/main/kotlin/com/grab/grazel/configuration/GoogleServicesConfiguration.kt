/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
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