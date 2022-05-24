/*
 * Copyright 2021 Grabtaxi Holdings PTE LTD (GRAB)
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

import com.grab.grazel.bazel.rules.BazelRepositoryRule
import com.grab.grazel.bazel.rules.HttpArchiveRule
import groovy.lang.Closure
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.property

internal const val RULES_JVM_EXTERNAL_NAME = "rules_jvm_external"
internal const val RULES_JVM_EXTERNAL_SHA256 =
    "f36441aa876c4f6427bfb2d1f2d723b48e9d930b62662bf723ddfb8fc80f0140"
internal const val RULES_JVM_EXTERNAl_TAG = "4.1"

internal val MAVEN_INSTALL_REPOSITORY = HttpArchiveRule(
    name = RULES_JVM_EXTERNAL_NAME,
    sha256 = RULES_JVM_EXTERNAL_SHA256,
    stripPrefix = "rules_jvm_external-%s".format(RULES_JVM_EXTERNAl_TAG),
    url = "https://github.com/bazelbuild/rules_jvm_external/archive/%s.zip".format(
        RULES_JVM_EXTERNAl_TAG
    )
)

/**
 * Configuration for [rules_jvm_external](github.com/bazelbuild/rules_jvm_external)'s maven_install rule.
 *
 * @param repository `WORKSPACE` repository details for `rules_jvm_external`
 * @param resolveTimeout Maps to `maven_install.resolve_timeout`
 * @param excludeArtifactsDenyList By default, per
 *      [artifact exclude rules](https://github.com/bazelbuild/rules_jvm_external#detailed-dependency-information-specifications)
 *      are automatically generated from Gradle, `excludeArtifactsDenyList` can be used to prevent an artifact from getting
 *      automatically excluded. Specify in maven `groupId:artifact` format.
 * @param excludeArtifacts Global exclude artifacts, maps to `maven_install.excluded_artifacts`. Specify in maven `groupId:artifact` format
 * @param overrideTargetLabels Map of `groupId:artifact` and bazel labels that will be specified to `maven_install.override_targets` param.
 * @param jetifyIncludeList Maven artifacts in `groupId:artifact` format that should be added `maven_install.jetify_include_list`
 * @param jetifyExcludeList Maven artifacts that should be excluded from `maven_install.jetify_include_list`
 */
data class MavenInstallExtension(
    private val objects: ObjectFactory,
    var repository: BazelRepositoryRule = MAVEN_INSTALL_REPOSITORY,
    var resolveTimeout: Int = 600,
    var artifactPinning: ArtifactPinning = ArtifactPinning(objects),
    var excludeArtifactsDenyList: ListProperty<String> = objects.listProperty(),
    var overrideTargetLabels: MapProperty<String, String> = objects.mapProperty(),
    var excludeArtifacts: ListProperty<String> = objects.listProperty(),
    var jetifyIncludeList: ListProperty<String> = objects.listProperty(),
    var jetifyExcludeList: ListProperty<String> = objects.listProperty()
) {
    // TODO GitRepositoryRule
    /**
     * Configure an HTTP Archive for `rules_jvm_external`.
     *
     * @param closure closure called with default value set to [MAVEN_INSTALL_REPOSITORY]
     */
    fun httpArchiveRepository(closure: Closure<*>) {
        repository = MAVEN_INSTALL_REPOSITORY
        closure.delegate = repository
        closure.call()
    }

    /**
     * Configure an HTTP Archive for `rules_jvm_external`.
     *
     * @param builder Builder called with default value of [MAVEN_INSTALL_REPOSITORY]
     */
    fun httpArchiveRepository(builder: HttpArchiveRule.() -> Unit) {
        repository = MAVEN_INSTALL_REPOSITORY.apply(builder)
    }

    fun artifactPinning(closure: Closure<*>) {
        closure.delegate = artifactPinning
        closure.call()
    }

    fun artifactPinning(builder: ArtifactPinning.() -> Unit) {
        artifactPinning = artifactPinning.apply(builder)
    }
}

data class ArtifactPinning(
    private val objects: ObjectFactory,
    var enabled: Property<Boolean> = objects.property<Boolean>().convention(true),
    // TODO Consider allowing different paths for maven_install.json
    val mavenInstallJson: String = "maven_install.json"
)