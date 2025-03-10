/*
 * Copyright 2025 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.migrate.common

import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.TestSize
import com.grab.grazel.extension.TestData
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject

internal class TestSizeCalculator
@Inject
constructor(
    private val grazelExtension: GrazelExtension,
) {
    private fun File.isTestFile() = name.endsWith("Test.kt")
        || name.endsWith("Tests.kt")

    private fun File.hasJUnitImport() = useLines { lines ->
        lines.any { it.trim().matches(Regex("""import\s+org\.junit\.Test""")) }
    }

    private fun File.countTestAnnotations() = useLines { lines ->
        lines.count { it.trim().startsWith("@Test") }
    }

    fun calculate(name: String, sources: Set<File>): TestSize = runBlocking {
        val result = sources.asFlow()
            .filter(File::exists)
            .flatMapMerge(concurrency = 4) { file ->
                file.walkBottomUp()
                    .filter { it.isFile }
                    .filter { it.isTestFile() }
                    .filter { it.hasJUnitImport() }
                    .asFlow()
            }.flatMapMerge(concurrency = 4) { file ->
                flow {
                    emit(TestFileData(file, file.countTestAnnotations()))
                }
            }.fold(initial = TestFileStats(0, 0)) { acc, fileData ->
                TestFileStats(
                    testCount = acc.testCount + fileData.testCount,
                    fileCount = acc.fileCount + 1
                )
            }
        val finalTestData = TestData(
            targetName = name,
            testsCount = result.testCount,
            testFileCount = result.fileCount
        )
        if (finalTestData.isValid) {
            grazelExtension.test.testSizeProvider(finalTestData)
        } else {
            TestSize.MEDIUM
        }
    }

    private data class TestFileData(val file: File, val testCount: Int)
    private data class TestFileStats(val testCount: Int, val fileCount: Int)

    companion object {
        internal val TestData.isValid: Boolean
            get() = testsCount > 0 && testFileCount > 0
    }
}