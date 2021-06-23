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

import com.grab.grazel.bazel.starlark.AssigneeBuilder
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.StringStatement
import com.grab.grazel.bazel.starlark.array
import com.grab.grazel.bazel.starlark.asString
import com.grab.grazel.bazel.starlark.assigneeBuilder
import com.grab.grazel.bazel.starlark.load
import com.grab.grazel.bazel.starlark.quote

private const val RULES_JVM_EXTERNAL_SHA = "RULES_JVM_EXTERNAL_SHA"
private const val RULES_JVM_EXTERNAL_TAG = "RULES_JVM_EXTERNAL_TAG"

sealed class MavenRepository : AssigneeBuilder {

    data class DefaultMavenRepository(
        val url: String,
        val username: String? = null,
        val password: String? = null
    ) : MavenRepository() {
        override fun build() = when {
            username == null || password == null -> StringStatement(url.quote())
            else -> StringStatement(url.split("://").joinToString(separator = "://$username:$password@").quote())
        }
    }
}

/**
 * External variables usually followed by the given array like `repository = EXTERNAL + [ ... ]`
 *
 * This method helps build an `Assignee` that composes this case.
 *
 * @param externalVariables The external variables that needs to be inject before given `arrayValues`
 * @param arrayValues The actual array values that needs to be combined with external variables.
 */
private fun combineExternalVariablesAndArray(
    externalVariables: List<String>,
    arrayValues: List<String>
) = assigneeBuilder {
    val externalRepositoryConversion = externalVariables.joinToString(separator = " + ")
    val extraPlus = if (externalVariables.isEmpty()) "" else "+"
    val repositoryArray = array(arrayValues).asString()
    StringStatement("$externalRepositoryConversion $extraPlus $repositoryArray")
}

fun StatementsBuilder.mavenInstall(
    name: String? = null,
    artifacts: List<String> = emptyList(),
    mavenRepositories: List<MavenRepository> = emptyList(),
    externalArtifacts: List<String> = emptyList(),
    externalRepositories: List<String> = emptyList(),
    jetify: Boolean = false,
    jetifyIncludeList: List<String> = emptyList(),
    failOnMissingChecksum: Boolean = true,
    resolveTimeout: Int = 600,
    excludeArtifacts: List<String> = emptyList()
) {
    load("@rules_jvm_external//:defs.bzl", "maven_install")
    load("@rules_jvm_external//:specs.bzl", "maven")

    rule("maven_install") {
        name?.let { "name" eq it.quote() }

        "artifacts" eq combineExternalVariablesAndArray(
            externalArtifacts,
            artifacts.map(String::quote)
        )

        "repositories" eq combineExternalVariablesAndArray(
            externalRepositories,
            mavenRepositories.map { it.build().asString() }
        )

        if (jetify) {
            "jetify" eq "True"
        }

        jetifyIncludeList.notEmpty {
            "jetify_include_list" eq array(jetifyIncludeList.quote)
        }

        if (!failOnMissingChecksum) {
            "fail_on_missing_checksum" eq "False"
        }

        "resolve_timeout" eq resolveTimeout
        excludeArtifacts.notEmpty {
            "excluded_artifacts" eq excludeArtifacts.quote
        }
    }
}

fun StatementsBuilder.jvmRules(
    rulesJvmTag: String = "3.3",
    rulesJvmSha: String = "d85951a92c0908c80bd8551002d66cb23c3434409c814179c0ff026b53544dab",
    resolveTimeout: Int = 600,
    artifacts: List<String> = emptyList(),
    mavenRepositories: List<MavenRepository> = emptyList(),
    externalArtifacts: List<String> = emptyList(),
    externalRepositories: List<String> = emptyList(),
    excludeArtifacts: List<String> = emptyList(),
    jetify: Boolean = false,
    jetifyIncludeList: List<String> = emptyList(),
    failOnMissingChecksum: Boolean = true
) {
    RULES_JVM_EXTERNAL_TAG eq rulesJvmTag.quote()
    RULES_JVM_EXTERNAL_SHA eq rulesJvmSha.quote()

    newLine()

    httpArchive(
        name = "rules_jvm_external",
        sha256 = RULES_JVM_EXTERNAL_SHA,
        stripPrefix = """"rules_jvm_external-%s" % $RULES_JVM_EXTERNAL_TAG""",
        url = """"https://github.com/bazelbuild/rules_jvm_external/archive/%s.zip" % $RULES_JVM_EXTERNAL_TAG"""
    )

    newLine()

    mavenInstall(
        artifacts = artifacts,
        mavenRepositories = mavenRepositories,
        externalArtifacts = externalArtifacts,
        externalRepositories = externalRepositories,
        jetify = jetify,
        jetifyIncludeList = jetifyIncludeList,
        failOnMissingChecksum = failOnMissingChecksum,
        resolveTimeout = resolveTimeout,
        excludeArtifacts = excludeArtifacts
    )
}