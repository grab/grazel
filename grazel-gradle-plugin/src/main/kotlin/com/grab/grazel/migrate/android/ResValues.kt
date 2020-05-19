/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate.android

import com.android.build.gradle.BaseExtension

data class ResValues(val stringValues: Map<String, String>) {
    fun exist() = stringValues.isNotEmpty()
}

fun BaseExtension.extractResValue(): ResValues =
    defaultConfig.resValues
        .mapValues { it.value.value }
        .run { ResValues(this) }