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