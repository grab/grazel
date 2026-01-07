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

package com.grab.grazel.fake

import com.grab.grazel.gradle.dependencies.DefaultDependencyGraphsService
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.dependencies.DependencyGraphsService

/**
 * A test fake for [DependencyGraphsService] that returns pre-configured [DependencyGraphs].
 *
 * Usage:
 * ```kotlin
 * val fakeService = FakeDependencyGraphsService(FakeDependencyGraphs())
 * val provider = project.provider { fakeService }
 * ```
 */
internal class FakeDependencyGraphsService(
    private val dependencyGraphs: DependencyGraphs = FakeDependencyGraphs()
) : DefaultDependencyGraphsService() {

    override fun get(): DependencyGraphs = dependencyGraphs

    override fun getParameters(): DependencyGraphsService.Params {
        throw UnsupportedOperationException("Not needed for tests")
    }
}
