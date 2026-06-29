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

import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.Test
import org.gradle.api.artifacts.Configuration

internal val Variant<*>.declaredDependencyConfigurations: Set<Configuration>
    get() = variantConfigurations
        .filterTo(linkedSetOf()) { configuration -> configuration.isDeclarationBucket }

internal val Variant<*>.compileOnlyDeclaredDependencyConfigurations: Set<Configuration>
    get() = variantConfigurations
        .filterTo(linkedSetOf()) { configuration -> configuration.isCompileOnlyDeclaration }

internal val Variant<*>.compileOnlyBucketName: String
    get() = when (variantType) {
        Test -> TEST_VARIANT
        AndroidTest -> ANDROID_TEST_VARIANT
        else -> name
    }

private val Configuration.isCompileOnlyDeclaration: Boolean
    get() {
        val normalizedName = name.lowercase()
        return "compileonly" in normalizedName && "dependenciesmetadata" !in normalizedName
    }

private val Configuration.isDeclarationBucket: Boolean
    get() {
        val normalizedName = name.lowercase()
        if (normalizedName.startsWith("_")) return false
        if (declarationBucketExcludedNameFragments.any { fragment -> fragment in normalizedName }) {
            return false
        }
        return declarationConfigurationSuffixes.any { suffix ->
            normalizedName.endsWith(suffix.lowercase())
        }
    }

private val declarationBucketExcludedNameFragments = setOf(
    "annotationprocessor",
    "androidapis",
    "androidjdkimage",
    "androidsdkimage",
    "archives",
    "classpath",
    "corelibrarydesugaring",
    "dependenciesmetadata",
    "jacoco",
    "kapt",
    "kotlin-extension",
    "kotlincompiler",
    "ksp",
    "lint",
    "reversemetadata"
)

private val declarationConfigurationSuffixes = listOf(
    "Implementation",
    "Api",
    "CompileOnly",
    "RuntimeOnly"
)

internal fun Configuration.declarationBucketName(): String {
    val prefix = declarationConfigurationSuffixes
        .firstNotNullOfOrNull { suffix ->
            name.removeSuffixIgnoringCase(suffix)
                .takeIf { bucketPrefix -> bucketPrefix.length != name.length }
        }
        .orEmpty()
    return prefix
        .takeUnless(String::isBlank)
        ?.replaceFirstChar { char -> char.lowercase() }
        ?: DEFAULT_VARIANT
}

private fun String.removeSuffixIgnoringCase(suffix: String): String {
    return if (endsWith(suffix, ignoreCase = true)) {
        dropLast(suffix.length)
    } else {
        this
    }
}
