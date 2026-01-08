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

/**
 * Data holder for KSP processor dependencies.
 *
 * @param group Maven group ID
 * @param name Maven artifact name
 * @param version Maven version
 * @param processorClass Fully-qualified class name of the KSP processor provider
 * @param generatesJava Whether this processor generates Java code
 * @param targetEmbeddedCompiler Whether to use embedded Kotlin compiler
 */
data class KspProcessor(
    val group: String,
    val name: String,
    val version: String?,
    val processorClass: String = "",
    val generatesJava: Boolean = false,
    val targetEmbeddedCompiler: Boolean = false
) : Comparable<KspProcessor> {
    /** Maven coordinate in format `group:name` */
    val shortId get() = "$group:$name"

    /** Maven coordinate in format `group:name:version` */
    val id get() = "$group:$name:${version ?: ""}"

    override fun compareTo(other: KspProcessor) = shortId.compareTo(other.shortId)
}

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
