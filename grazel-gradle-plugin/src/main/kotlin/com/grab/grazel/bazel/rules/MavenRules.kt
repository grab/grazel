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

import com.grab.grazel.bazel.starlark.AssigneeBuilder
import com.grab.grazel.bazel.starlark.StarlarkType
import com.grab.grazel.bazel.starlark.StatementsBuilder
import com.grab.grazel.bazel.starlark.StringStatement
import com.grab.grazel.bazel.starlark.add
import com.grab.grazel.bazel.starlark.array
import com.grab.grazel.bazel.starlark.asString
import com.grab.grazel.bazel.starlark.assigneeBuilder
import com.grab.grazel.bazel.starlark.load
import com.grab.grazel.bazel.starlark.obj
import com.grab.grazel.bazel.starlark.quote

sealed class MavenRepository : AssigneeBuilder {

    data class DefaultMavenRepository(
        val url: String,
        val username: String? = null,
        val password: String? = null
    ) : MavenRepository() {
        override fun build() = when {
            username == null || password == null -> StringStatement(url.quote())
            else -> StringStatement(
                url.split("://").joinToString(separator = "://$username:$password@").quote()
            )
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
 * @param appendExternal Indicates whether to append or prepend the External variables
 */
private fun combineExternalVariablesAndArray(
    externalVariables: List<String>,
    arrayValues: List<String>,
    appendExternal: Boolean = false,
) = assigneeBuilder {
    val externalRepositoryConversion = externalVariables.joinToString(separator = " + ")
    val extraPlus = if (externalVariables.isEmpty()) "" else "+"
    val repositoryArray = array(arrayValues).asString()

    if (appendExternal) {
        StringStatement("$repositoryArray $extraPlus $externalRepositoryConversion")
    } else {
        StringStatement("$externalRepositoryConversion $extraPlus $repositoryArray")
    }

}

fun StatementsBuilder.mavenInstall(
    name: String? = null,
    rulesJvmExternalName: String,
    artifacts: Set<MavenInstallArtifact> = emptySet(),
    mavenRepositories: List<MavenRepository> = emptyList(),
    externalArtifacts: List<String> = emptyList(),
    externalRepositories: List<String> = emptyList(),
    jetify: Boolean = false,
    mavenInstallJson: String? = null,
    jetifyIncludeList: List<String> = emptyList(),
    failOnMissingChecksum: Boolean = true,
    resolveTimeout: Int = 600,
    excludeArtifacts: List<String> = emptyList(),
    overrideTargets: Map<String, String> = emptyMap(),
    versionConflictPolicy: String? = null,
) {
    load("@$rulesJvmExternalName//:defs.bzl", "maven_install")
    load("@$rulesJvmExternalName//:specs.bzl", "maven")

    rule("maven_install") {
        name?.let { "name" eq it.quote() }

        "artifacts" eq combineExternalVariablesAndArray(
            externalArtifacts,
            artifacts.map { it.asString() }
        )

        "repositories" eq combineExternalVariablesAndArray(
            externalRepositories,
            mavenRepositories.map { it.build().asString() },
            true,
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

        if (overrideTargets.isNotEmpty()) {
            "override_targets" eq obj {
                overrideTargets.forEach { (mavenArtifact, bazelLabel) ->
                    mavenArtifact.quote() eq bazelLabel.quote()
                }
            }
        }
        mavenInstallJson?.let {
            "maven_install_json" eq mavenInstallJson.quote()
        }

        versionConflictPolicy?.let {
            "version_conflict_policy" eq it.quote()
        }
    }
}

/**
 * Type representing artifacts in `rules_jvm_external`'s `maven_install` rule.
 *
 * This assumes `load("@rules_jvm_external//:specs.bzl", "maven")` is already loaded
 */
sealed class MavenInstallArtifact : StarlarkType {
    // Full maven coordinates
    abstract val id: String

    data class SimpleArtifact(
        val coordinates: String,
    ) : MavenInstallArtifact() {
        override val id: String get() = coordinates

        override fun StatementsBuilder.statements() {
            add(coordinates.quote())
        }
    }

    /**
     * Type representing artifacts in `maven_install`'s `maven.artifact`
     */
    sealed class Exclusion : StarlarkType {
        data class SimpleExclusion(
            val coordinates: String
        ) : Exclusion() {
            override fun StatementsBuilder.statements() {
                add(coordinates.quote())
            }
        }

        data class DetailedExclusion(
            val group: String,
            val artifact: String
        ) : Exclusion() {
            override fun StatementsBuilder.statements() {
                rule("maven.exclusion") {
                    "group" eq group.quote()
                    "artifact" eq artifact.quote()
                }
            }
        }
    }

    data class DetailedArtifact(
        val group: String,
        val artifact: String,
        val version: String,
        val exclusions: List<Exclusion>,
    ) : MavenInstallArtifact() {
        override val id: String = "$group:$artifact:$version"
        override fun StatementsBuilder.statements() {
            rule("maven.artifact") {
                "group" eq group.quote()
                "artifact" eq artifact.quote()
                "version" eq version.quote()
                "exclusions" eq exclusions.map { it.asString() }
            }
        }
    }
}

fun StatementsBuilder.jvmRules(
    rulesJvmExternalRule: BazelRepositoryRule,
    resolveTimeout: Int = 600,
    artifacts: Set<MavenInstallArtifact> = emptySet(),
    artifactPinning: Boolean,
    mavenInstallJson: String? = null,
    mavenRepositories: List<MavenRepository> = emptyList(),
    externalArtifacts: List<String> = emptyList(),
    externalRepositories: List<String> = emptyList(),
    excludeArtifacts: List<String> = emptyList(),
    overrideTargets: Map<String, String> = emptyMap(),
    jetify: Boolean = false,
    jetifyIncludeList: List<String> = emptyList(),
    failOnMissingChecksum: Boolean = true,
    versionConflictPolicy: String? = null,
) {
    add(rulesJvmExternalRule)

    newLine()

    mavenInstall(
        rulesJvmExternalName = rulesJvmExternalRule.name,
        artifacts = artifacts,
        mavenRepositories = mavenRepositories,
        externalArtifacts = externalArtifacts,
        externalRepositories = externalRepositories,
        overrideTargets = overrideTargets,
        mavenInstallJson = mavenInstallJson,
        jetify = jetify,
        jetifyIncludeList = jetifyIncludeList,
        failOnMissingChecksum = failOnMissingChecksum,
        resolveTimeout = resolveTimeout,
        excludeArtifacts = excludeArtifacts,
        versionConflictPolicy = versionConflictPolicy,
    )

    if (artifactPinning) {
        load("@maven//:defs.bzl", "pinned_maven_install")
        add("pinned_maven_install()")
    }
}