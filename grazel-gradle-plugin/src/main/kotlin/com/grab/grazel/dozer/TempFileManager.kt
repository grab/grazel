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

package com.grab.grazel.dozer

import com.google.common.io.Files
import org.apache.commons.io.FileUtils
import java.io.File


interface TempFileManager {
    fun workSpaceFileToTempFile()
    fun tempFileToWorkSpaceFile()
    fun deleteTempDir()
}

internal const val TEMP_DIR_PATH = "build/temp"
internal const val TEMP_FILE_PATH = "$TEMP_DIR_PATH/BUILD.bazel"

internal class DefaultTempFileManager(private val rootDir: File) : TempFileManager {
    private val workspaceFile = File(rootDir, "WORKSPACE")
    private val tempDir = File(rootDir, TEMP_DIR_PATH).also {
        if (!it.exists()) it.mkdirs()
    }
    private val tempBuildFile = File(rootDir, TEMP_FILE_PATH)

    override fun workSpaceFileToTempFile() = Files.copy(workspaceFile, tempBuildFile)
    override fun tempFileToWorkSpaceFile() = Files.copy(tempBuildFile, workspaceFile)

    override fun deleteTempDir() {
        if (tempBuildFile.exists()) FileUtils.forceDelete(tempBuildFile)
        if (tempDir.exists()) FileUtils.forceDelete(tempDir)
    }
}