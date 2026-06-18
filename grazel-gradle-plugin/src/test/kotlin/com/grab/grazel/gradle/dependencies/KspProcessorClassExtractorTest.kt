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

package com.grab.grazel.gradle.dependencies

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals

class KspProcessorClassExtractorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `processor class extraction uses exact file paths when jar basenames collide`() {
        val firstJar = processorJar(
            directory = temporaryFolder.newFolder("first"),
            processorClass = "com.example.first.ProcessorProvider"
        )
        val secondJar = processorJar(
            directory = temporaryFolder.newFolder("second"),
            processorClass = "com.example.second.ProcessorProvider"
        )

        val processorClasses = KspProcessorClassExtractor.extractProcessorClasses(
            artifactJars = setOf(firstJar, secondJar),
            artifactMapping = mapOf(
                "com.example.first:processor" to firstJar.absolutePath,
                "com.example.second:processor" to secondJar.absolutePath
            )
        )

        assertEquals(
            mapOf(
                "com.example.first:processor" to "com.example.first.ProcessorProvider",
                "com.example.second:processor" to "com.example.second.ProcessorProvider"
            ),
            processorClasses
        )
    }

    private fun processorJar(directory: File, processorClass: String): File {
        val jar = File(directory, "processor-1.0.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(
                ZipEntry("META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider")
            )
            zip.write(processorClass.toByteArray())
            zip.closeEntry()
        }
        return jar
    }
}
