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

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.io.File
import java.util.zip.ZipFile

private const val KSP_SERVICE_FILE = "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider"

/**
 * Extracts processor class names from KSP processor JARs.
 * Reads META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider
 * to determine the fully-qualified class name of the processor provider.
 */
internal object KspProcessorClassExtractor {

    /**
     * Extracts processor classes from all resolved KSP artifacts in the configuration.
     * @param configuration The Gradle configuration containing KSP processor dependencies
     * @return Map of "group:name" to list of processor class names
     */
    fun extractProcessorClasses(configuration: Configuration): Map<String, List<String>> {
        // Skip non-resolvable configurations
        if (!configuration.isCanBeResolved) {
            return emptyMap()
        }

        val result = mutableMapOf<String, List<String>>()

        configuration.incoming
            .artifactView {
                isLenient = true
                componentFilter { it is ModuleComponentIdentifier }
            }
            .artifacts
            .forEach { artifact ->
                val componentId = artifact.id.componentIdentifier
                if (componentId is ModuleComponentIdentifier) {
                    val key = "${componentId.group}:${componentId.module}"
                    val classes = readProcessorClasses(artifact.file)
                    if (classes.isNotEmpty()) {
                        result[key] = classes
                    }
                }
            }

        return result
    }

    /**
     * Reads processor class names from a JAR file's service file.
     * @param jarFile The JAR file to read
     * @return List of fully-qualified processor class names, or empty if not found
     */
    private fun readProcessorClasses(jarFile: File): List<String> {
        if (!jarFile.exists() || !jarFile.name.endsWith(".jar")) {
            return emptyList()
        }

        return try {
            ZipFile(jarFile).use { zip ->
                val entry = zip.getEntry(KSP_SERVICE_FILE) ?: return emptyList()
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
