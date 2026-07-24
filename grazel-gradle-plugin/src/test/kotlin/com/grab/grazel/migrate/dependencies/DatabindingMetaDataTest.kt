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

package com.grab.grazel.migrate.dependencies

import com.google.common.truth.Truth.assertThat
import com.grab.grazel.maven.MavenCoordinates
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DatabindingMetaDataTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `databindingPackage reads the package from the br bin entry`() {
        val aar = aar(
            "AndroidManifest.xml",
            "data-binding/com.example.feature-br.bin",
            "classes.jar"
        )
        assertThat(databindingPackage(aar)).isEqualTo("com.example.feature")
    }

    @Test
    fun `databindingPackage returns null when no br bin entry exists`() {
        assertThat(databindingPackage(aar("AndroidManifest.xml", "classes.jar"))).isNull()
    }

    @Test
    fun `databindingPackage picks the first br bin entry`() {
        val aar = aar(
            "data-binding/com.example.first-br.bin",
            "data-binding/com.example.second-br.bin"
        )
        assertThat(databindingPackage(aar)).isEqualTo("com.example.first")
    }

    @Test
    fun `renderDatabindingInfoBazelrc renders an empty flag value for no packages`() {
        assertThat(renderDatabindingInfoBazelrc(emptyMap())).isEqualTo(
            "# Generated file. DO NOT MODIFY.\nbuild --android_databinding_package_info="
        )
    }

    @Test
    fun `renderDatabindingInfoBazelrc joins packages with commas`() {
        val rendered = renderDatabindingInfoBazelrc(
            linkedMapOf(
                "androidx_databinding_databinding_adapters" to "androidx.databinding.library.baseAdapters",
                "com_grab_feature" to "com.grab.feature"
            )
        )
        assertThat(rendered).isEqualTo(
            "# Generated file. DO NOT MODIFY.\n" +
                "build --android_databinding_package_info=" +
                "androidx_databinding_databinding_adapters=androidx.databinding.library.baseAdapters," +
                "com_grab_feature=com.grab.feature"
        )
    }

    @Test
    fun `databindingBazelName mangles group and module separators`() {
        val coordinates = MavenCoordinates(
            group = "androidx.databinding",
            module = "databinding-adapters",
            version = "8.6.1"
        )
        assertThat(coordinates.databindingBazelName())
            .isEqualTo("androidx_databinding_databinding_adapters")
    }

    private fun aar(vararg entryNames: String): File {
        val aar = temporaryFolder.newFile("test.aar")
        ZipOutputStream(aar.outputStream()).use { zip ->
            entryNames.forEach { entryName ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(entryName.toByteArray())
                zip.closeEntry()
            }
        }
        return aar
    }
}
