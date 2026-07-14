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

package com.grab.grazel.tasks.internal

import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.ProjectLayout
import org.gradle.process.ExecOperations
import java.io.File

/**
 * Formats [source] out-of-place through a temp file rather than in-place, because buildifier
 * infers file type (BUILD vs .bzl vs WORKSPACE) from the path/name it is given, which may not
 * match [source]'s staged name. The temp copy therefore keeps [source]'s original filename so
 * buildifier's inference stays correct, and only after formatting is the result copied back under
 * [destination]'s real name - losing either rename would either mis-detect the file type or leave
 * the formatted output under the wrong filename.
 */
internal fun formatWithBuildifier(
    buildifierScript: File,
    source: File,
    destination: File,
    execOperations: ExecOperations,
    fileSystemOperations: FileSystemOperations,
    projectLayout: ProjectLayout,
) {
    if (!source.exists()) return

    val tmpFile = projectLayout
        .buildDirectory
        .file("grazel/${source.name}.tmp")
        .get()
        .asFile
    fileSystemOperations.copy {
        from(source)
        into(tmpFile.parentFile)
        rename { tmpFile.name }
    }
    execOperations.exec {
        commandLine = listOf(
            buildifierScript.absolutePath,
            tmpFile.absolutePath,
        )
    }
    fileSystemOperations.copy {
        from(tmpFile)
        into(destination.parentFile)
        rename { destination.name }
    }
}
