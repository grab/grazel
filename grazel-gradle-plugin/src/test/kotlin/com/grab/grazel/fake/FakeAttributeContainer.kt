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

package com.grab.grazel.fake

import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.provider.Provider

class FakeAttributeContainer : AttributeContainer {
    override fun getAttributes(): AttributeContainer {
        TODO("Not yet implemented")
    }

    override fun keySet(): MutableSet<Attribute<*>> {
        TODO("Not yet implemented")
    }

    override fun <T : Any?> attribute(p0: Attribute<T>, p1: T): AttributeContainer {
        TODO("Not yet implemented")
    }

    override fun <T : Any?> attributeProvider(
        p0: Attribute<T>,
        p1: Provider<out T>
    ): AttributeContainer {
        TODO("Not yet implemented")
    }

    override fun <T : Any?> getAttribute(p0: Attribute<T>): T? {
        TODO("Not yet implemented")
    }

    override fun isEmpty(): Boolean {
        TODO("Not yet implemented")
    }

    override fun contains(p0: Attribute<*>): Boolean {
        TODO("Not yet implemented")
    }
}