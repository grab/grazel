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

package com.grab.grazel.configuration

import com.grab.grazel.bazel.rules.BazelRepositoryRule
import com.grab.grazel.bazel.rules.GitRepositoryRule
import com.grab.grazel.bazel.rules.HttpArchiveRule
import com.grab.grazel.bazel.starlark.quote
import groovy.lang.Closure

internal const val RULE_KOTLIN_NAME = "io_bazel_rules_kotlin"
internal const val RULES_KOTLIN_SHA = "da0e6e1543fcc79e93d4d93c3333378f3bd5d29e82c1bc2518de0dbe048e6598"
internal const val RULES_KOTLIN_VERSION = "legacy-1.4.0-rc3"

/**
 * Options for Kotlin Compiler.
 *
 * @see [https://bazelbuild.github.io/rules_kotlin/kotlin#kt_kotlinc_options]
 */
data class KotlinCOptions(
    var useIr: Boolean = false
)

/**
 * Options for Java Compiler.
 *
 * @see [https://bazelbuild.github.io/rules_kotlin/kotlin#kt_javac_options]
 */
class JavaCOptions

/**
 * Configuration for Kotlin Toolchain.
 *
 * @see [https://bazelbuild.github.io/rules_kotlin/kotlin#define_kt_toolchain]
 */
class KotlinToolChain(
    var name: String = "kotlin_toolchain",
    var enabled: Boolean = false,
    var apiVersion: String = "1.4",
    var reportUnusedDeps: String = "off",
    var strictKotlinDeps: String = "off",
    var abiJars: Boolean = false,
    var multiplexWorkers: Boolean = false,
    var languageVersion: String = "1.4",
    var jvmTarget: String = "1.8"
)

internal val KOTLIN_REPOSITORY = HttpArchiveRule(
    name = RULE_KOTLIN_NAME,
    sha256 = RULES_KOTLIN_SHA,
    url = """"https://github.com/bazelbuild/rules_kotlin/releases/download/%s/rules_kotlin_release.tgz" % ${RULES_KOTLIN_VERSION.quote()}"""
)

data class KotlinCompiler(
    var version: String = "1.4.21",
    var sha: String = "46720991a716e90bfc0cf3f2c81b2bd735c14f4ea6a5064c488e04fd76e6b6c7"
)

/**
 * Configuration for Kotlin compiler and toolchains. Options configured will be used in root `BUILD.bazel`, `WORKSPACE`
 * respectively.
 *
 * Usage:
 * ```kotlin
 * kotlin {
 *      kotlinC {
 *          useIr = true
 *      }
 *      javaC {
 *         // java compiler options
 *      }
 *      toolchain {
 *         apiVersion = "1.4"
 *         reportUnusedDeps = "warn"
 *         strictKotlinDeps = "warn"
 *         abiJars = true
 *         languageVersion = "1.4"
 *      }
 * }
 * ```
 */
data class KotlinConfiguration(
    val compiler: KotlinCompiler = KotlinCompiler(),
    val kotlinCOptions: KotlinCOptions = KotlinCOptions(),
    val javaCOptions: JavaCOptions = JavaCOptions(),
    val toolchain: KotlinToolChain = KotlinToolChain(),
    var repository: BazelRepositoryRule = KOTLIN_REPOSITORY,
    var enabledTransitiveReduction : Boolean = false
) {
    fun kotlinC(block: KotlinCOptions.() -> Unit) {
        block(kotlinCOptions)
    }

    fun kotlinC(closure: Closure<*>) {
        closure.delegate = kotlinCOptions
        closure.call()
    }

    fun javaC(block: JavaCOptions.() -> Unit) {
        block(javaCOptions)
    }

    fun javaC(closure: Closure<*>) {
        closure.delegate = javaCOptions
        closure.call()
    }

    fun toolchain(block: KotlinToolChain.() -> Unit) {
        block(toolchain)
    }

    fun toolchain(closure: Closure<*>) {
        closure.delegate = toolchain
        closure.call()
    }

    fun compiler(block: KotlinCompiler.() -> Unit) {
        block(compiler)
    }

    fun compiler(closure: Closure<*>) {
        closure.delegate = compiler
        closure.call()
    }

    fun gitRepository(closure: Closure<*>) {
        repository = GitRepositoryRule(name = RULE_KOTLIN_NAME, remote = "")
        closure.delegate = repository
        closure.call()
    }

    fun gitRepository(builder: GitRepositoryRule.() -> Unit) {
        repository = GitRepositoryRule(name = RULE_KOTLIN_NAME, remote = "").apply(builder)
    }

    /**
     * Configure a HTTP Archive for `rules_kotlin`.
     *
     * @param closure closure called with default value set to [KOTLIN_REPOSITORY]
     */
    fun httpArchiveRepository(closure: Closure<*>) {
        repository = KOTLIN_REPOSITORY
        closure.delegate = repository
        closure.call()
    }

    /**
     * Configure a HTTP Archive for `rules_kotlin`.
     *
     * @param builder Builder called with default value of [KOTLIN_REPOSITORY]
     */
    fun httpArchiveRepository(builder: HttpArchiveRule.() -> Unit) {
        repository = KOTLIN_REPOSITORY.apply(builder)
    }
}
