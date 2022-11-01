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

package com.grab.grazel.bazel.starlark

/**
 *  Represents an entity in generated startlark code. This can be anything from single [Statement] to collection of
 *  [Statement]. Implementing types must provide their starlark representation via [StarlarkType.statements] method.
 */
interface StarlarkType {
    /**
     * Build the starlark representation of this type with the provided `StatementsBuilder`
     *
     * @receiver The `StatementsBuilder` instance to which the contents must be written to.
     */
    fun StatementsBuilder.statements()
}

private fun StarlarkType.addTo(statementsBuilder: StatementsBuilder) {
    statementsBuilder.statements()
}

fun StatementsBuilder.add(starlarkType: StarlarkType) {
    starlarkType.addTo(this)
}

fun StarlarkType.asString() = Assignee { addTo(this) }.asString()