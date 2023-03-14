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

package com.grab.grazel

import com.grab.grazel.extension.AndroidExtension
import com.grab.grazel.extension.DependenciesExtension
import com.grab.grazel.extension.HybridExtension
import com.grab.grazel.extension.RulesExtension
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

    val android = AndroidExtension()

    val dependencies = DependenciesExtension(rootProject.objects)

    val rules = RulesExtension(rootProject.objects)

    val hybrid = HybridExtension(rootProject.objects)

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
     * @param block Configuration block with [AndroidExtension] as the receiver
     * @see AndroidExtension
     */
    fun android(block: AndroidExtension.() -> Unit) {
        block(android)
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
     * @param closure Closue for configuration with [AndroidExtension] instance as the delegate
     * @see AndroidExtension
     */
    fun android(closure: Closure<*>) {
        closure.delegate = android
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
     * @param block Configuration block with [DependenciesExtension] as the receiver
     * @see DependenciesExtension
     */
    fun dependencies(block: DependenciesExtension.() -> Unit) {
        block(dependencies)
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
     * @param closure Closure for configuration with [DependenciesExtension] instance as delegate
     * @see DependenciesExtension
     */
    fun dependencies(closure: Closure<*>) {
        closure.delegate = dependencies
        closure.call()
    }

    /**
     * Top level rules configuration block to configure various rules. For list of available rule configurations
     *
     * ```
     * rules {
     *   bazelCommon {
     *   }
     * }
     * ```
     * @param block Configuration block with [RulesExtension] as the receiver
     * @see RulesExtension
     */
    fun rules(block: RulesExtension.() -> Unit) {
        block(rules)
    }

    /**
     * Top level rules configuration block to configure various rules. For list of available rule configurations
     *
     * ```
     * rules {
     *   bazelCommon {
     *   }
     * }
     * ```
     * @param closure Closure block for configuration with [RulesExtension] as the delegate
     * @see RulesExtension
     */
    fun rules(closure: Closure<*>) {
        closure.delegate = rules
        closure.call()
    }

    /**
     * Extension to configure hybrid build related configurations.
     *
     * ```
     * hybrid {
     *
     * }
     * ```
     * @see HybridExtension
     * @param block Configuration block with [HybridExtension] as the receiver
     */
    fun hybrid(block: HybridExtension.() -> Unit) {
        block(hybrid)
    }

    /**
     * Extension to configure hybrid build related configurations.
     *
     * ```
     * hybrid {
     *
     * }
     * ```
     * @see HybridExtension
     * @param closure Closure block with [HybridExtension] as the delegate
     */
    fun hybrid(closure: Closure<*>) {
        closure.delegate = hybrid
        closure.call()
    }
}
