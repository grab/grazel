/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
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