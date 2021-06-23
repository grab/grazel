/*
 * Copyright 2021 Grabtaxi Holdings PTE LTE (GRAB)
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

import com.grab.grazel.bazel.rules.KotlinProjectType.Android
import com.grab.grazel.bazel.rules.KotlinProjectType.Jvm
import com.grab.grazel.bazel.rules.Visibility.Public
import com.grab.grazel.bazel.starlark.Assignee
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.array
import com.grab.grazel.bazel.starlark.asString
import com.grab.grazel.bazel.starlark.glob
import com.grab.grazel.bazel.starlark.load
import com.grab.grazel.bazel.starlark.obj
import com.grab.grazel.bazel.starlark.quote
import com.grab.grazel.configuration.JavaCOptions
import com.grab.grazel.configuration.KotlinCOptions
import com.grab.grazel.configuration.KotlinToolChain

/**
 * `WORKSPACE` rule that registers the given [repositoryRule].
 */
fun StatementsBuilder.kotlinRepository(repositoryRule: BazelRepositoryRule) {
    repositoryRule.addTo(this)
    if (repositoryRule is GitRepositoryRule) {
        // If repository is git repository then transitive dependencies of Kotlin repo needs to be manually added
        load("@io_bazel_rules_kotlin//kotlin:dependencies.bzl", "kt_download_local_dev_dependencies")
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
    KOTLIN_VERSION eq kotlinCompilerVersion.quote()
    KOTLINC_RELEASE_SHA eq kotlinCompilerReleaseSha.quote()
    newLine()

    KOTLINC_RELEASE eq obj {
        "urls".quote() eq array(
            """"https://github.com/JetBrains/kotlin/releases/download/v{v}/kotlin-compiler-{v}.zip".format(v = $KOTLIN_VERSION)"""
        )
        "sha256".quote() eq KOTLINC_RELEASE_SHA
    }

    load("@io_bazel_rules_kotlin//kotlin:kotlin.bzl", "kotlin_repositories")
    add("""kotlin_repositories(compiler_release = $KOTLINC_RELEASE)""")
}

/**
 * `WORKSPACE` rule to generate Kotlin toolchain rule. If `toolchain.enabled` is set to `false`, will use the default Kotlin
 * otherwise will use the custom toolchain parameters.
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
            "@io_bazel_rules_kotlin//kotlin:kotlin.bzl",
            javaTarget,
            kotlinCTarget,
            "define_kt_toolchain"
        )
        rule(kotlinCTarget) {
            "name" eq kotlinCTarget.quote()
            "x_use_ir" eq kotlinCOptions.useIr.toString().capitalize()
        }
        rule(javaTarget) {
            "name" eq javaTarget.quote()
        }

        rule("define_kt_toolchain") {
            "name" eq toolchain.name.quote()
            "api_version" eq toolchain.apiVersion.quote()
            "experimental_use_abi_jars" eq toolchain.abiJars.toString().capitalize()
            "experimental_multiplex_workers" eq toolchain.multiplexWorkers.toString().capitalize()
            "javac_options" eq "//:$javaTarget".quote()
            "jvm_target" eq toolchain.jvmTarget.quote()
            "kotlinc_options" eq "//:$kotlinCTarget".quote()
            "language_version" eq toolchain.languageVersion.quote()
            "experimental_report_unused_deps" eq toolchain.reportUnusedDeps.quote()
            "experimental_strict_kotlin_deps" eq toolchain.strictKotlinDeps.quote()
        }
    }
}

fun StatementsBuilder.loadKtRules(
    isAndroid: Boolean = false,
    isJvm: Boolean = false,
    hasDb: Boolean = false
) {
    when {
        hasDb -> load(
            "@$GRAB_BAZEL_COMMON//tools/databinding:databinding.bzl",
            "kt_db_android_library"
        )
        isAndroid -> load("@$GRAB_BAZEL_COMMON//tools/kotlin:android.bzl", "kt_android_library")
        isJvm -> load("@io_bazel_rules_kotlin//kotlin:kotlin.bzl", "kt_jvm_library")
    }
}

sealed class KotlinProjectType {
    object Jvm : KotlinProjectType()
    data class Android(val hasDatabinding: Boolean = false) : KotlinProjectType()
}

fun StatementsBuilder.ktLibrary(
    name: String,
    kotlinProjectType: KotlinProjectType = Jvm,
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
    tags: List<String> = emptyList()
) {
    loadKtRules(
        isAndroid = kotlinProjectType is Android,
        isJvm = kotlinProjectType is Jvm,
        hasDb = (kotlinProjectType as? Android)?.hasDatabinding == true
    )
    val ruleName = when (kotlinProjectType) {
        Jvm -> "kt_jvm_library"
        is Android -> if (kotlinProjectType.hasDatabinding) {
            "kt_db_android_library"
        } else {
            "kt_android_library"
        }
    }

    rule(ruleName) {
        "name" eq name.quote()
        srcs.notEmpty {
            "srcs" eq srcs.map(String::quote)
        }
        srcsGlob.notEmpty {
            "srcs" eq glob(srcsGlob.map(String::quote))
        }
        "visibility" eq array(visibility.rule.quote())
        deps.notEmpty {
            "deps" eq array(deps.map(BazelDependency::toString).map(String::quote))
        }
        resourceFiles.notEmpty {
            "resource_files" eq resourceFiles.joinToString(separator = " + ", transform = Assignee::asString)
        }
        resources.notEmpty {
            "resource_files" eq glob(resources.quote)
        }
        packageName?.let { "custom_package" eq packageName.quote() }
        manifest?.let { "manifest" eq manifest.quote() }
        plugins.notEmpty {
            "plugins" eq array(plugins.map(BazelDependency::toString).map(String::quote))
        }
        assetsDir?.let {
            "assets" eq glob(assetsGlob.quote)
            "assets_dir" eq assetsDir.quote()
        }

        tags.notEmpty {
            "tags" eq array(tags.map(String::quote))
        }
    }
}