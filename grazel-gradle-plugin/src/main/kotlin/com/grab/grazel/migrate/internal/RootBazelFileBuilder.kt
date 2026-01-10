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
import com.grab.grazel.bazel.rules.KspProcessor
import com.grab.grazel.bazel.rules.androidExtensionsRules
import com.grab.grazel.bazel.rules.configureCommonToolchains
import com.grab.grazel.bazel.rules.daggerBuildRules
import com.grab.grazel.bazel.rules.kspPluginRules
import com.grab.grazel.bazel.rules.rootKotlinSetup
import com.grab.grazel.bazel.starlark.Statement
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.exportsFiles
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.extension.KspProcessorConfig
import com.grab.grazel.gradle.GradleProjectInfo
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.migrate.BazelFileBuilder
import javax.inject.Inject
import javax.inject.Singleton

internal class RootBazelFileBuilder(
    private val gradleProjectInfo: GradleProjectInfo,
    private val grazelExtension: GrazelExtension,
    private val workspaceDependencies: WorkspaceDependencies,
) : BazelFileBuilder {

    @Singleton
    class Factory
    @Inject
    constructor(private val grazelExtension: GrazelExtension) {
        fun create(
            gradleProjectInfo: GradleProjectInfo,
            workspaceDependencies: WorkspaceDependencies
        ) = RootBazelFileBuilder(
            gradleProjectInfo,
            grazelExtension,
            workspaceDependencies
        )
    }

    override fun build(): List<Statement> = statements {
        setupKotlin()
        if (gradleProjectInfo.hasDagger) daggerBuildRules()
        if (gradleProjectInfo.hasAndroidExtension) androidExtensionsRules()

        val kspProcessors = buildKspProcessors()
        if (kspProcessors.isNotEmpty()) kspPluginRules(kspProcessors)

        configureCommonToolchains(
            bazelCommonRepoName = grazelExtension.rules.bazelCommon.repository.name,
            toolchains = grazelExtension.rules.bazelCommon.toolchains
        )
        if (gradleProjectInfo.rootLintXml.exists()) {
            exportsFiles(gradleProjectInfo.rootLintXml.name)
        }
    }

    private fun buildKspProcessors(): Set<KspProcessor> {
        if (workspaceDependencies.kspResult.isEmpty()) return emptySet()
        val kspProcessorConfigs = grazelExtension.rules.kotlin.ksp.processors
        return workspaceDependencies.kspResult.values
            .mapNotNull { dep ->
                val processorClass = dep.processorClass
                if (processorClass.isNullOrEmpty()) return@mapNotNull null
                val (group, name, version) = dep.id.split(":")
                val config = kspProcessorConfigs["$group:$name"] ?: KspProcessorConfig()
                KspProcessor(
                    group = group,
                    name = name,
                    version = version,
                    processorClass = processorClass,
                    generatesJava = config.generatesJava,
                    targetEmbeddedCompiler = config.targetEmbeddedCompiler
                )
            }.toSortedSet()
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