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

import groovy.lang.Closure
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.kotlin.dsl.listProperty

/**
 * Configuration for dependencies
 *
 * @param ignoreArtifacts The artifacts to ignore for migration. Any [Project] instance using any of the `ignoreArtifacts`
 *                        will not be migrated.
 * @param overrideArtifactVersions List of fully qualified Maven coordinated names that will be used instead of actual value
 *                                in generated code.
 */
data class DependenciesExtension(
    private val objects: ObjectFactory,
    val ignoreArtifacts: ListProperty<String> = objects.listProperty(),
    val overrideArtifactVersions: ListProperty<String> = objects.listProperty()
)

/**
 * Configuration for generated rules.
 *
 * Each rules' configuration should have it's own configuration block, for example:
 * ```
 * rules {
 *  bazelCommon {
 *     commit = ""
 *  }
 * }
 * ```
 */
data class RulesExtension(
    private val objects: ObjectFactory,
    val bazelCommon: BazelCommonExtension = BazelCommonExtension(),
    val googleServices: GoogleServicesExtension = GoogleServicesExtension(),
    val mavenInstall: MavenInstallExtension = MavenInstallExtension(objects),
    val kotlin: KotlinExtension = KotlinExtension(),
    val test: TestExtension = TestExtension(),
    val dagger: DaggerExtension = DaggerExtension()
) {
    fun bazelCommon(block: BazelCommonExtension.() -> Unit) {
        block(bazelCommon)
    }

    fun bazelCommon(closure: Closure<*>) {
        closure.delegate = bazelCommon
        closure.call()
    }

    fun mavenInstall(block: MavenInstallExtension.() -> Unit) {
        block(mavenInstall)
    }

    fun mavenInstall(closure: Closure<*>) {
        closure.delegate = mavenInstall
        closure.call()
    }

    fun googleServices(block: GoogleServicesExtension.() -> Unit) {
        block(googleServices)
    }

    fun googleServices(closure: Closure<*>) {
        closure.delegate = googleServices
        closure.call()
    }

    fun kotlin(closure: Closure<*>) {
        closure.delegate = kotlin
        closure.call()
    }

    fun kotlin(block: KotlinExtension.() -> Unit) {
        block(kotlin)
    }

    fun dagger(closure: Closure<*>) {
        closure.delegate = dagger
        closure.call()
    }

    fun dagger(block: DaggerExtension.() -> Unit) {
        block(dagger)
    }

    fun test(closure: Closure<*>) {
        closure.delegate = test
        closure.call()
    }

    fun test(block: TestExtension.() -> Unit) {
        block(test)
    }
}

