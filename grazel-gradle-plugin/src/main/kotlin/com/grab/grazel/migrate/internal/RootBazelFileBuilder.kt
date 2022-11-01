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

package com.grab.grazel.migrate.internal

import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.rules.androidExtensionsRules
import com.grab.grazel.bazel.rules.configureCommonToolchains
import com.grab.grazel.bazel.rules.daggerBuildRules
import com.grab.grazel.bazel.rules.rootKotlinSetup
import com.grab.grazel.bazel.starlark.Statement
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.gradle.GradleProjectInfo
import com.grab.grazel.migrate.BazelFileBuilder
import javax.inject.Inject

internal class RootBazelFileBuilder @Inject constructor(
    private val gradleProjectInfo: GradleProjectInfo,
    private val grazelExtension: GrazelExtension
) : BazelFileBuilder {

    override fun build(): List<Statement> = statements {
        setupKotlin()
        if (gradleProjectInfo.hasDagger) daggerBuildRules()
        if (gradleProjectInfo.hasAndroidExtension) androidExtensionsRules()
        configureCommonToolchains(
            bazelCommonRepoName = grazelExtension.rules.bazelCommon.repository.name,
            toolchains = grazelExtension.rules.bazelCommon.toolchains
        )
    }

    private fun StatementsBuilder.setupKotlin() {
        val kotlinConfiguration = grazelExtension.rules.kotlin
        rootKotlinSetup(
            kotlinCOptions = kotlinConfiguration.kotlinCOptions,
            javaCOptions = kotlinConfiguration.javaCOptions,
            toolchain = kotlinConfiguration.toolchain
        )
    }
}