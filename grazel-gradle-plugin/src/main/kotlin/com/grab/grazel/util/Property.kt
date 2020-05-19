/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.util

import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

typealias GradleProvider<T> = Provider<T>
typealias GradleProperty<T> = Property<T>

/**
 * Sets a value to this [Property] and disallow further changes by calling [Property.finalizeValueOnRead]
 *
 * @receiver The Property instance for which the value needs to be set
 */
internal fun <T> Property<T>.setFinal(value: T) {
    set(value)
    disallowChanges()
    finalizeValueOnRead()
}