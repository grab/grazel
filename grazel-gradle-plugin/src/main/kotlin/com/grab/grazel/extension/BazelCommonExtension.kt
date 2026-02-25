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

package com.grab.grazel.extension

import com.grab.grazel.bazel.rules.GRAB_BAZEL_COMMON
import com.grab.grazel.bazel.rules.GitRepositoryRule
import groovy.lang.Closure
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property

/**
 * Configuration for http archives needed before bazel_common initialization
 * (e.g., remote_java_tools, rules_java_builtin, rules_java)
 */
open class PreBazelCommonArchive(
    open var name: String = "",
    open var sha256: String = "",
    open var url: String = "",
    open var urls: List<String> = emptyList(),
    open var stripPrefix: String? = null,
    open var patches: List<String> = emptyList(),
    open var patchArgs: List<String> = emptyList(),
)

class BazelCommonExtension(
    private val objects: ObjectFactory? = null,
    var repository: GitRepositoryRule = GitRepositoryRule(name = GRAB_BAZEL_COMMON),
    var toolchains: CommonToolchainExtension = CommonToolchainExtension(),
    private val _preBazelCommonArchives: MutableList<PreBazelCommonArchive> = mutableListOf(),
) {
    var pinnedMavenInstall: Property<Boolean>? = objects?.property<Boolean>()?.convention(true)
    var additionalCoursierOptions: ListProperty<String>? = objects?.listProperty<String>()?.convention(
        listOf("--parallel", "12")
    )

    var preBazelCommonArchives: List<PreBazelCommonArchive>
        get() = _preBazelCommonArchives
        set(value) {
            _preBazelCommonArchives.clear()
            _preBazelCommonArchives.addAll(value)
        }

    fun gitRepository(closure: Closure<*>) {
        closure.delegate = repository
        closure.call()
    }

    fun gitRepository(builder: GitRepositoryRule.() -> Unit) {
        builder(repository)
    }

    fun toolchains(closure: Closure<*>) {
        closure.delegate = toolchains
        closure.call()
    }

    fun toolchains(builder: CommonToolchainExtension.() -> Unit) {
        builder(toolchains)
    }
}