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

package com.grab.grazel

import com.grab.grazel.configuration.AndroidConfiguration
import com.grab.grazel.configuration.DependenciesConfiguration
import com.grab.grazel.configuration.RulesConfiguration
import groovy.lang.Closure
import org.gradle.api.Project

/**
 * The extension allows to configure various Grazel attributes like migration behavior and generated rules configuration.
 *
 * For example
 * ```
 * grazel {
 *    android {
 *    }
 *    rules {
 *      bazelCommon {
 *      }
 *    }
 * }
 * ```
 *
 * @param rootProject The root project instance injected from [GrazelGradlePlugin]
 */
open class GrazelExtension(
    val rootProject: Project
) {
    companion object {
        const val GRAZEL_EXTENSION = "grazel"
    }

    val androidConfiguration = AndroidConfiguration()

    val dependenciesConfiguration = DependenciesConfiguration(rootProject.objects)

    val rulesConfiguration = RulesConfiguration(rootProject.objects)

    /**
     * Android specific configuration used to configure parameters for android_binary or other android related
     * rules
     *
     * ```
     * android {
     *   variantFilter {
     *   }
     *   ...
     * }
     * ```
     *
     * @param block Configuration block with [AndroidConfiguration] as the receiver
     */
    fun android(block: AndroidConfiguration.() -> Unit) {
        block(androidConfiguration)
    }

    /**
     * Android specific configuration used to configure parameters for android_binary or other android related
     * rules
     *
     * ```
     * android {
     *   variantFilter {
     *   }
     *   ...
     * }
     * ```
     * @param closure Closue for configuration with [AndroidConfiguration] instance as the delegate
     */
    fun android(closure: Closure<*>) {
        closure.delegate = androidConfiguration
        closure.call()
    }

    /**
     * Dependencies configuration used to control how dependencies should be handled during migration. For example,
     *
     * ```
     * dependencies {
     *   ignoreArtifacts = []
     *   ...
     * }
     * ```
     *
     * @param block Configuration block with [DependenciesConfiguration] as the receiver
     */
    fun dependencies(block: DependenciesConfiguration.() -> Unit) {
        block(dependenciesConfiguration)
    }

    /**
     * Dependencies configuration used to control how dependencies should be handled during migration. For example,
     *
     * ```
     * dependencies {
     *   ignoreArtifacts = []
     *   ...
     * }
     * ```
     * @param closure Closure for configuration with [DependenciesConfiguration] instance as delegate
     */
    fun dependencies(closure: Closure<*>) {
        closure.delegate = dependenciesConfiguration
        closure.call()
    }

    /**
     * Top level rules configuration block to configure various rules. For list of available rule configurations, see
     * [RulesConfiguration]
     *
     * ```
     * rules {
     *   bazelCommon {
     *   }
     * }
     * ```
     * @param block Configuration block with [RulesConfiguration] as the receiver
     */
    fun rules(block: RulesConfiguration.() -> Unit) {
        block(rulesConfiguration)
    }

    /**
     * Top level rules configuration block to configure various rules. For list of available rule configurations, see
     * [RulesConfiguration]
     *
     * ```
     * rules {
     *   bazelCommon {
     *   }
     * }
     * ```
     * @param closure Closure block for configuration with [RulesConfiguration] as the delegate
     */
    fun rules(closure: Closure<*>) {
        closure.delegate = rulesConfiguration
        closure.call()
    }
}