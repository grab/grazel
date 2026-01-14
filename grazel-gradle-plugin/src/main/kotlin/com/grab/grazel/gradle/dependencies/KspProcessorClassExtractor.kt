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

package com.grab.grazel.gradle.dependencies

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

private const val KSP_SERVICE_FILE =
    "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider"

/**
 * Extracts processor class names from KSP processor JARs.
 * Reads META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider
 * to determine the fully-qualified class name of the processor provider.
 *
 * Results are cached to avoid re-reading the same JAR across variants.
 */
internal object KspProcessorClassExtractor {

    // TODO: Move cache to a Gradle BuildService for proper lifecycle management
    private val cache = ConcurrentHashMap<String, List<String>>()

    /**
     * Extracts processor classes from KSP artifact JARs.
     *
     * @param artifactJars Collection of JAR files to scan
     * @param artifactMapping Map of "group:artifact" shortId to JAR filename
     * @return Map of shortId to processor class name
     */
    fun extractProcessorClasses(
        artifactJars: Set<File>,
        artifactMapping: Map<String, String>
    ): Map<String, String> {
        val jarsByName = artifactJars.associateBy { it.name }
        return artifactMapping.mapNotNull { (shortId, fileName) ->
            val jarFile = jarsByName[fileName] ?: return@mapNotNull null
            readProcessorClasses(jarFile).firstOrNull()?.let { shortId to it }
        }.toMap()
    }

    /**
     * Reads processor class names from a JAR file's service file.
     * Results are cached by absolute path.
     *
     * @param jarFile The JAR file to read
     * @return List of fully-qualified processor class names, or empty if not found
     */
    private fun readProcessorClasses(jarFile: File): List<String> {
        if (!jarFile.exists() || !jarFile.name.endsWith(".jar")) {
            return emptyList()
        }

        return cache.getOrPut(jarFile.absolutePath) {
            try {
                ZipFile(jarFile).use { zip ->
                    val entry = zip.getEntry(KSP_SERVICE_FILE) ?: return@getOrPut emptyList()
                    zip.getInputStream(entry).bufferedReader().useLines { lines ->
                        lines
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                            .toList()
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
