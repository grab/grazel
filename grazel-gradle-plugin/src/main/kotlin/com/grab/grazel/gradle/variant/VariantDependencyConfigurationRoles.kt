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

/**
 * A configuration is only treated as a real user-facing dependency-declaration bucket (as opposed
 * to an internal/tooling configuration Gradle happens to name similarly) if it survives two
 * filters: it must not contain any [declarationBucketExcludedNameFragments] fragment (these catch
 * internal buckets like `kapt`/`ksp`/`lint`/`...DependenciesMetadata` that also end in one of the
 * declaration suffixes below), and it must end with one of [declarationConfigurationSuffixes].
 * Missing an exclusion here silently reclassifies an internal configuration as a declared
 * dependency bucket, corrupting downstream dependency resolution.
 */
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

/**
 * Recovers the bucket/flavor name a configuration was declared for by stripping the first
 * matching declaration suffix (case-insensitively, since Gradle configuration names mix case at
 * the suffix boundary e.g. `debugImplementation`). If no suffix matches or stripping it leaves an
 * empty prefix (e.g. plain `implementation`), the configuration is considered to belong to the
 * default variant bucket rather than a flavor-specific one - callers rely on this fallback to
 * bucket base-variant dependencies correctly.
 */
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
