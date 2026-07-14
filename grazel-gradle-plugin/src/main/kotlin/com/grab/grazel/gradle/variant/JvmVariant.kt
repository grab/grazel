/*
 * Copyright 2026 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.gradle.variant

import com.google.common.base.MoreObjects
import com.grab.grazel.gradle.hasKapt
import com.grab.grazel.gradle.hasKsp
import com.grab.grazel.gradle.variant.VariantType.JvmBuild
import com.grab.grazel.gradle.variant.VariantType.Lint
import com.grab.grazel.gradle.variant.VariantType.Test
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

class JvmVariantData(
    val project: Project,
    val variantType: VariantType,
    val name: String = when (variantType) {
        JvmBuild -> DEFAULT_VARIANT
        Lint -> LINT_VARIANT
        else -> TEST_VARIANT
    }
)

fun JvmVariant(project: Project, variantType: VariantType) = JvmVariant(
    JvmVariantData(
        project,
        variantType
    )
)

/**
 * Jvm libraries don't have variants like Android projects do hence this type is used to encapsulate
 * Jvm specific information in `Variant` class.
 *
 * @see DefaultVariants
 */
class JvmVariant(
    private val jvmVariantData: JvmVariantData
) : Variant<JvmVariantData> {
    override val name: String get() = jvmVariantData.name
    override val backingVariant: JvmVariantData get() = jvmVariantData
    override val project: Project get() = jvmVariantData.project
    override val variantType: VariantType get() = jvmVariantData.variantType

    override val variantConfigurations: Set<Configuration>
        get() = project.configurations.filter {
            when (variantType) {
                Test -> it.name.contains("test", true)
                else -> !it.name.contains("test", true)
            }
        }.toSet()

    override val extendsFrom: Set<String> = emptySet()

    // Store name to configurations to avoid lookup cost for below configurations parsing
    private val configurationNameMap = project.configurations.associateBy { it.name }

    override val compileConfiguration: Set<Configuration>
        get() = setOf(
            configurationNameMap.getValue(
                when {
                    variantType.isTest -> "testCompileClasspath"
                    else -> "compileClasspath"
                }
            )
        )

    override val runtimeConfiguration: Set<Configuration>
        get() = setOf(
            configurationNameMap.getValue(
                when {
                    variantType.isTest -> "testRuntimeClasspath"
                    else -> "runtimeClasspath"
                }
            )
        )

    override val annotationProcessorConfiguration: Set<Configuration>
        get() = buildSet {
            add(
                if (project.hasKapt) when (variantType) {
                    JvmBuild -> configurationNameMap.getValue("kapt")
                    else -> configurationNameMap.getValue("kaptTest")
                } else when (variantType) {
                    JvmBuild -> configurationNameMap.getValue("testAnnotationProcessor")
                    else -> configurationNameMap.getValue("annotationProcessor")
                }
            )
        }

    override val kspConfiguration: Set<Configuration>
        get() = buildSet {
            if (project.hasKsp) {
                // KSP creates *KotlinProcessorClasspath configs that are resolvable
                val configName = when (variantType) {
                    JvmBuild -> "kspKotlinProcessorClasspath"
                    else -> "kspTestKotlinProcessorClasspath"
                }
                configurationNameMap[configName]?.let(::add)
            }
        }

    override val kotlinCompilerPluginConfiguration: Set<Configuration>
        get() = buildSet {
            val configName = "kotlinCompilerPluginClasspath"
            add(
                when (variantType) {
                    Test -> configurationNameMap.getValue("${configName}Test")
                    else -> configurationNameMap.getValue("${configName}Main")
                }
            )
        }

    override fun toString(): String = MoreObjects.toStringHelper(this)
        .add("project", project.name)
        .add("name", name)
        .add("variantType", variantType)
        .toString()
}
