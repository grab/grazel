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

import com.grab.grazel.bazel.TestSize
import com.grab.grazel.bazel.rules.Visibility.Public
import com.grab.grazel.bazel.starlark.Assignee
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.add
import com.grab.grazel.bazel.starlark.array
import com.grab.grazel.bazel.starlark.asString
import com.grab.grazel.bazel.starlark.glob
import com.grab.grazel.bazel.starlark.load
import com.grab.grazel.bazel.starlark.quote
import com.grab.grazel.bazel.starlark.toObject
import com.grab.grazel.extension.JavaCOptions
import com.grab.grazel.extension.KotlinCOptions
import com.grab.grazel.extension.KotlinToolChain
import com.grab.grazel.migrate.android.LintConfigData

/** `WORKSPACE` rule that registers the given [repositoryRule]. */
fun StatementsBuilder.kotlinRepository(repositoryRule: BazelRepositoryRule) {
    add(repositoryRule)
    if (repositoryRule is GitRepositoryRule) {
        // If repository is git repository then transitive dependencies of Kotlin repo needs to be manually added
        load(
            "@io_bazel_rules_kotlin//kotlin:dependencies.bzl",
            "kt_download_local_dev_dependencies"
        )
        add("kt_download_local_dev_dependencies()")
    }
}


private const val KOTLIN_VERSION = "KOTLIN_VERSION"
private const val KOTLINC_RELEASE = "KOTLINC_RELEASE"
private const val KOTLINC_RELEASE_SHA = "KOTLINC_RELEASE_SHA"

fun StatementsBuilder.kotlinCompiler(
    kotlinCompilerVersion: String,
    kotlinCompilerReleaseSha: String
) {
    KOTLIN_VERSION `=` kotlinCompilerVersion.quote
    KOTLINC_RELEASE_SHA `=` kotlinCompilerReleaseSha.quote
    newLine()

    load(
        "@io_bazel_rules_kotlin//kotlin:repositories.bzl",
        "kotlin_repositories",
        "kotlinc_version"
    )

    KOTLINC_RELEASE `=` """kotlinc_version(
        release = $KOTLIN_VERSION,
        sha256 = $KOTLINC_RELEASE_SHA
    )
    """.trimIndent()

    add("""kotlin_repositories(compiler_release = $KOTLINC_RELEASE)""")
}

/**
 * `WORKSPACE` rule to generate Kotlin toolchain rule. If `toolchain.enabled` is set to `false`,
 * will use the default Kotlin otherwise will use the custom toolchain parameters.
 */
fun StatementsBuilder.registerKotlinToolchain(toolchain: KotlinToolChain) {
    if (toolchain.enabled) {
        registerToolchain("//:${toolchain.name}")
    } else {
        // Fallback to default toolchains
        load("@io_bazel_rules_kotlin//kotlin:kotlin.bzl", "kt_register_toolchains")
        add("kt_register_toolchains()")
    }
}

/**
 * `BUILD.bazel` rules for defining custom Kotlin toolchains
 *
 * @param kotlinCOptions Kotlin compiler options, generated with `kt_kotlinc_options` rule.
 * @param javaCOptions Arguments to pass to `javac`, generated with `kt_javac_options` rule
 * @param toolchain Kotlin toolchain options generated with `define_kt_toolchain`
 */
fun StatementsBuilder.rootKotlinSetup(
    kotlinCOptions: KotlinCOptions,
    javaCOptions: JavaCOptions,
    toolchain: KotlinToolChain
) {
    if (toolchain.enabled) {
        val kotlinCTarget = "kt_kotlinc_options"
        val javaTarget = "kt_javac_options"
        load(
            "@io_bazel_rules_kotlin//kotlin:core.bzl",
            javaTarget,
            kotlinCTarget,
            "define_kt_toolchain"
        )
        rule(kotlinCTarget) {
            "name" `=` kotlinCTarget.quote
            if (kotlinCOptions.useIr != null) {
                "x_use_ir" `=` kotlinCOptions.useIr.toString().capitalize()
            }
        }
        rule(javaTarget) {
            "name" `=` javaTarget.quote
        }

        rule("define_kt_toolchain") {
            "name" `=` toolchain.name.quote
            "api_version" `=` toolchain.apiVersion.quote
            "experimental_use_abi_jars" `=` toolchain.abiJars.toString().capitalize()
            "experimental_multiplex_workers" `=` toolchain.multiplexWorkers.toString().capitalize()
            "javac_options" `=` "//:$javaTarget".quote
            "jvm_target" `=` toolchain.jvmTarget.quote
            "kotlinc_options" `=` "//:$kotlinCTarget".quote
            "language_version" `=` toolchain.languageVersion.quote
            "experimental_report_unused_deps" `=` toolchain.reportUnusedDeps.quote
            "experimental_strict_kotlin_deps" `=` toolchain.strictKotlinDeps.quote
        }
    }
}

fun StatementsBuilder.ktLibrary(
    name: String,
    srcs: List<String> = emptyList(),
    packageName: String? = null,
    srcsGlob: List<String> = emptyList(),
    visibility: Visibility = Public,
    deps: List<BazelDependency> = emptyList(),
    resources: List<String> = emptyList(),
    resourceFiles: List<Assignee> = emptyList(),
    manifest: String? = null,
    plugins: List<BazelDependency> = emptyList(),
    assetsGlob: List<String> = emptyList(),
    assetsDir: String? = null,
    tags: List<String> = emptyList(),
    lintConfigData: LintConfigData? = null,
) {
    load("@$GRAB_BAZEL_COMMON//rules:defs.bzl", "kotlin_library")

    rule("kotlin_library") {
        "name" `=` name.quote
        srcs.notEmpty {
            "srcs" `=` srcs.map(String::quote)
        }
        srcsGlob.notEmpty {
            "srcs" `=` glob(srcsGlob.map(String::quote))
        }
        "visibility" `=` array(visibility.rule.quote)
        deps.notEmpty {
            "deps" `=` array(deps.map(BazelDependency::toString).map(String::quote))
        }
        resourceFiles.notEmpty {
            "resource_files" `=` resourceFiles.joinToString(
                separator = " + ",
                transform = Assignee::asString
            )
        }
        resources.notEmpty {
            "resource_files" `=` glob(resources.quote)
        }
        packageName?.let { "custom_package" `=` packageName.quote }
        manifest?.let { "manifest" `=` manifest.quote }
        plugins.notEmpty {
            "plugins" `=` array(plugins.map(BazelDependency::toString).map(String::quote))
        }
        assetsDir?.let {
            "assets" `=` glob(assetsGlob.quote)
            "assets_dir" `=` assetsDir.quote
        }

        tags.notEmpty {
            "tags" `=` array(tags.map(String::quote))
        }

        if (lintConfigData?.merged?.isNotEmpty() == true) {
            "lint_options" `=` lintConfigData.merged.toObject()
        }
    }
}


fun StatementsBuilder.kotlinTest(
    name: String,
    srcs: List<String> = emptyList(),
    additionalSrcSets: List<String> = emptyList(),
    srcsGlob: List<String> = emptyList(),
    visibility: Visibility = Public,
    associates: List<BazelDependency> = emptyList(),
    deps: List<BazelDependency> = emptyList(),
    plugins: List<BazelDependency> = emptyList(),
    tags: List<String> = emptyList(),
    testSize: TestSize = TestSize.MEDIUM,
) {
    load("@$GRAB_BAZEL_COMMON//rules:defs.bzl", "kotlin_test")

    rule("kotlin_test") {
        "name" `=` name.quote
        srcs.notEmpty {
            "srcs" `=` srcs.map(String::quote)
        }
        srcsGlob.notEmpty {
            "srcs" `=` glob(srcsGlob.map(String::quote))
        }
        "size" `=` testSize.name.lowercase().quote
        additionalSrcSets.notEmpty {
            "additional_src_sets" `=` additionalSrcSets.map(String::quote)
        }
        "visibility" `=` array(visibility.rule.quote)
        deps.notEmpty {
            "deps" `=` array(deps.map(BazelDependency::toString).map(String::quote))
        }
        associates.notEmpty {
            "associates" `=` array(associates.map(BazelDependency::toString).map(String::quote))
        }
        plugins.notEmpty {
            "plugins" `=` array(plugins.map(BazelDependency::toString).map(String::quote))
        }
        tags.notEmpty {
            "tags" `=` array(tags.map(String::quote))
        }
    }
}