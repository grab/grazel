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

package com.grab.grazel.hybrid

import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.exec.bazelCommand
import com.grab.grazel.bazel.exec.executeCommand
import com.grab.grazel.di.qualifiers.RootProject
import com.grab.grazel.gradle.isMigrated
import com.grab.grazel.util.KT_INTERMEDIATE_TARGET_SUFFIX
import org.gradle.api.Project
import javax.inject.Inject
import javax.inject.Singleton

internal interface HybridBuildExecutor {

    fun buildAarTargets()

    fun execute()
}

@Singleton
internal class DefaultHybridBuildExecutor
@Inject constructor(
    @param:RootProject private val rootProject: Project,
    private val grazelExtension: GrazelExtension,
    private val dependencySubstitution: DependencySubstitution
) : HybridBuildExecutor {

    private val enabled get() = grazelExtension.hybrid.enabled.get()

    /**
     * Given a combined sequence of `android_library` and `kt_android_library` targets will return
     * unique targets i.e `android_library` alone.
     */
    internal fun findUniqueAarTargets(aarTargets: Sequence<String>): List<String> {
        // Filter out _base target added by kt_android_library
        val allAarTargets = aarTargets.map {
            if (it.endsWith(KT_INTERMEDIATE_TARGET_SUFFIX)) {
                it.split(KT_INTERMEDIATE_TARGET_SUFFIX).first()
            } else it
        }
        // The remaining unique entries are aar targets
        return mutableMapOf<String, String>()
            .apply {
                allAarTargets.forEach { target ->
                    if (containsKey(target))
                        remove(target)
                    else {
                        put(target, target)
                    }
                }
            }.keys
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { "$it.aar" }
            .toList()
    }


    private fun collectDatabindingAarTargets(aarTargets: Sequence<String>) = aarTargets
        .filter { it.isNotEmpty() }
        .map { "$it.aar" }
        .toList()

    override fun buildAarTargets() {
        // Query android library targets
        val (bazelAarOut, _) = rootProject.executeCommand(
            "bazelisk",
            "query",
            "kind(android_library, //...:*)"
        )

        // Query databinding aars
        val (databindingAar, _) = rootProject.executeCommand(
            "bazelisk",
            "query",
            "kind(databinding_aar, //...:*)"
        )

        val aarTargets = findUniqueAarTargets(bazelAarOut.lineSequence()) +
            collectDatabindingAarTargets(databindingAar.lineSequence())
        rootProject.logger.quiet("Found aar targets : $aarTargets")

        rootProject.bazelCommand(
            "build",
            *aarTargets.distinct().toTypedArray(),
            ignoreExit = true
        )
    }

    override fun execute() {
        if (rootProject.isMigrated && enabled) {
            rootProject.bazelCommand("build", "//...")
            buildAarTargets()
            rootProject.bazelCommand("shutdown")
            dependencySubstitution.register()
        }
    }
}
