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

package com.grab.grazel.migrate.android

import com.android.build.gradle.api.BaseVariant
import com.grab.grazel.bazel.starlark.filegroup
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.bazel.starlark.writeToFile
import com.grab.grazel.util.BUILD_BAZEL
import org.gradle.api.Project
import java.io.File
import javax.inject.Inject


interface KeyStoreExtractor {
    fun extract(rootProject: Project, variant: BaseVariant?): String?
}

/**
 * Utility class to ensure android keystore can be consumed as a bazel target
 */
class DefaultKeyStoreExtractor @Inject constructor() : KeyStoreExtractor {
    /**
     * For the given variant, will extract keystore file if it exists and ensures corresponding BUILD.bazel is generated
     *
     * @param rootProject The root project of current migration
     * @param variant The android variant for which key store needs to be extracted
     * @return The bazel target string for the keystore
     */
    override fun extract(rootProject: Project, variant: BaseVariant?): String? = variant
        ?.buildType
        ?.signingConfig
        ?.storeFile
        ?.let { storeFile ->
            if (storeFile.exists()) {
                val relativePath = rootProject.relativePath(storeFile)
                if (!relativePath.startsWith("..")) { // Keystore not present in project dir, so ignore
                    val keystoreDir = storeFile.parentFile
                    val keystoreBuildBazel = File(keystoreDir, BUILD_BAZEL)
                    val targetName = storeFile.name.replace(".", "-")
                    // Generate build.bazel for keystore
                    statements {
                        filegroup(name = targetName, srcs = listOf(storeFile.name))
                    }.writeToFile(keystoreBuildBazel)
                    // Format it
                    rootProject.exec {
                        commandLine = listOf("buildifier", keystoreBuildBazel.absolutePath)
                    }
                    return "//${rootProject.relativePath(keystoreDir)}:$targetName"
                }
            }
            null
        }
}