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

package com.grab.grazel.bazel.rules

import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.array
import com.grab.grazel.bazel.starlark.load
import com.grab.grazel.bazel.starlark.quote
import com.grab.grazel.gradle.KspProcessor
import com.grab.grazel.gradle.hasKsp
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalDependency

/**
 * Generates the target name for a KSP plugin rule.
 * Converts maven coordinates to a valid Bazel target name.
 * Example: "com.squareup.moshi:moshi-kotlin-codegen" -> "moshi-kotlin-codegen-ksp"
 */
fun kspTargetName(processor: KspProcessor): String {
    return "${processor.name}-ksp"
}

/**
 * Creates a BazelDependency reference to a KSP plugin target in root BUILD.bazel.
 * Example: //:room-compiler-ksp
 */
fun kspPluginTarget(processor: KspProcessor): BazelDependency =
    BazelDependency.StringDependency("//:" + kspTargetName(processor))

/**
 * Converts a KSP processor to its maven label format.
 * Example: "@ksp_maven//:androidx_room_room_compiler"
 */
private fun KspProcessor.toMavenLabel(): String {
    val groupPart = group.replace(".", "_").replace("-", "_")
    val namePart = name.replace(".", "_").replace("-", "_")
    return "@ksp_maven//:${groupPart}_$namePart"
}

/**
 * Generates `kt_ksp_plugin` rules in root BUILD.bazel for all KSP processors.
 * These consolidated targets can be referenced by modules using KSP.
 *
 * Example output:
 * ```starlark
 * load("@io_bazel_rules_kotlin//kotlin:core.bzl", "kt_ksp_plugin")
 *
 * kt_ksp_plugin(
 *     name = "dagger-compiler-ksp",
 *     deps = ["@maven//:com_google_dagger_dagger_compiler"],
 *     processor_class = "dagger.internal.codegen.KspComponentProcessor$Provider",
 *     generates_java = True,
 *     visibility = ["//visibility:public"],
 * )
 * ```
 */
fun StatementsBuilder.kspPluginRules(kspProcessors: Set<KspProcessor>) {
    if (kspProcessors.isEmpty()) return

    load("@io_bazel_rules_kotlin//kotlin:core.bzl", "kt_ksp_plugin")

    kspProcessors.forEach { processor ->
        rule("kt_ksp_plugin") {
            "name" `=` kspTargetName(processor).quote
            "deps" `=` array(listOf(processor.toMavenLabel()).map(String::quote))
            "processor_class" `=` processor.processorClass.quote
            "generates_java" `=` processor.generatesJava.toString().replaceFirstChar { it.titlecase() }
            "target_embedded_compiler" `=` processor.targetEmbeddedCompiler.toString().replaceFirstChar { it.titlecase() }
            "visibility" `=` array(listOf("//visibility:public".quote))
        }
    }
}

/**
 * Extracts KSP plugin targets for a module based on its KSP configurations.
 * Returns references to root-level kt_ksp_plugin targets (e.g., //:room-compiler-ksp).
 *
 * @param project The project to extract KSP plugin targets from
 * @return List of BazelDependency references to root-level KSP plugin targets
 */
internal fun kspPluginDeps(project: Project): List<BazelDependency> {
    if (!project.hasKsp) return emptyList()

    // Filter out KSP's own internal dependencies (symbol-processing-*)
    val kspInternalGroup = "com.google.devtools.ksp"

    return project.configurations
        .filter { config ->
            val name = config.name
            name.startsWith("ksp") &&
                !name.contains("Test", ignoreCase = true) &&
                !name.contains("AndroidTest", ignoreCase = true)
        }
        .flatMap { config -> config.allDependencies.filterIsInstance<ExternalDependency>() }
        .filter { dep -> dep.group != kspInternalGroup }
        .distinctBy { "${it.group}:${it.name}" }
        .map { dep ->
            val processor = KspProcessor(
                group = dep.group ?: "",
                name = dep.name,
                version = dep.version
            )
            kspPluginTarget(processor)
        }
}
