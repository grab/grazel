/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
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

import java.lang.reflect.Field

internal fun Class<*>.findField(fieldName: String): Field? {
    val field = try {
        getDeclaredField(fieldName)
    } catch (e: NoSuchFieldException) {
        null
    }
    return field ?: superclass?.findField(fieldName)
}

internal fun <T> Any.fieldValue(fieldName: String): T? {
    val field = this.javaClass.findField(fieldName)?.also { it.isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    return field?.get(this) as? T?
}