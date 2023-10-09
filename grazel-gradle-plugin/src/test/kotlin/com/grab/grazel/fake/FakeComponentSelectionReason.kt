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

import org.gradle.api.artifacts.result.ComponentSelectionDescriptor
import org.gradle.api.artifacts.result.ComponentSelectionReason

class FakeComponentSelectionReason : ComponentSelectionReason {
    override fun isForced(): Boolean = false
    override fun isConflictResolution(): Boolean = false
    override fun isSelectedByRule(): Boolean = false
    override fun isExpected(): Boolean = false
    override fun isCompositeSubstitution(): Boolean = false
    override fun isConstrained(): Boolean = false
    override fun getDescriptions(): MutableList<ComponentSelectionDescriptor> = mutableListOf()
}