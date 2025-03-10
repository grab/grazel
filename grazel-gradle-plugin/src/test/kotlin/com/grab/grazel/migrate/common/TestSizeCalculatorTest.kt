package com.grab.grazel.migrate.common

import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.TestSize
import com.grab.grazel.buildProject
import com.grab.grazel.migrate.common.TestSizeCalculator.Companion.isValid
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertTrue

class TestSizeCalculatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var grazelExtension: GrazelExtension
    private lateinit var testSizeCalculator: TestSizeCalculator

    @Before
    fun setup() {
        val rootProject = buildProject("dummy")
        grazelExtension = GrazelExtension(rootProject).apply {
            test {
                testSizeProvider = { TestSize.SMALL }
            }
        }
        testSizeCalculator = TestSizeCalculator(grazelExtension)
    }

    private fun createTestFile(path: String, content: String): File {
        return temporaryFolder.newFile(path).apply {
            writeText(content.trimIndent())
        }
    }

    @Test
    fun `test calculate returns MEDIUM when no valid test files found`() {
        val nonTestFile = createTestFile(
            path = "Example.kt",
            content = """
                package com.example
                class Example
            """
        )

        val result = testSizeCalculator.calculate("example", setOf(nonTestFile))
        assertEquals(TestSize.MEDIUM, result)
    }

    @Test
    fun `test calculate returns correct size for single test file`() {
        val testFile = createTestFile(
            path = "ExampleTest.kt",
            content = """
                package com.example
                import org.junit.Test
                
                class ExampleTest {
                    @Test
                    fun test1() {}
                    
                    @Test
                    fun test2() {}
                }
            """
        )

        grazelExtension.test.testSizeProvider = { testData ->
            assertTrue(testData.isValid)
            assertEquals("example", testData.targetName)
            assertEquals(2, testData.testsCount)
            assertEquals(1, testData.testFileCount)
            TestSize.LARGE
        }

        val result = testSizeCalculator.calculate("example", setOf(testFile))
        assertEquals(TestSize.LARGE, result)
    }

    @Test
    fun `test calculate handles multiple test files`() {
        val testFile1 = createTestFile(
            path = "FirstTest.kt",
            content = """
                package com.example
                import org.junit.Test
                
                class FirstTest {
                    @Test
                    fun test1() {}
                }
            """
        )

        val testFile2 = createTestFile(
            path = "SecondTest.kt",
            content = """
                package com.example
                import org.junit.Test
                
                class SecondTest {
                    @Test
                    fun test2() {}
                    
                    @Test
                    fun test3() {}
                }
            """
        )

        grazelExtension.test.testSizeProvider = { testData ->
            assertTrue(testData.isValid)
            assertEquals("example", testData.targetName)
            assertEquals(3, testData.testsCount)
            assertEquals(2, testData.testFileCount)
            TestSize.ENORMOUS
        }

        val result = testSizeCalculator.calculate("example", setOf(testFile1, testFile2))
        assertEquals(TestSize.ENORMOUS, result)
    }

    @Test
    fun `test calculate ignores non-test files and files without JUnit imports`() {
        val regularFile = createTestFile(
            path = "Regular.kt",
            content = "class Regular"
        )

        val nonJunitTestFile = createTestFile(
            path = "NonJunitTest.kt",
            content = """
                class NonJunitTest {
                    @Test // Not a JUnit test
                    fun test() {}
                }
            """
        )

        val validTestFile = createTestFile(
            path = "ValidTest.kt",
            content = """
                import org.junit.Test
                
                class ValidTest {
                    @Test
                    fun test() {}
                }
            """
        )

        grazelExtension.test.testSizeProvider = { testData ->
            assertEquals(1, testData.testsCount)
            assertEquals(1, testData.testFileCount)
            TestSize.SMALL
        }

        val result = testSizeCalculator.calculate(
            "example",
            setOf(regularFile, nonJunitTestFile, validTestFile)
        )
        assertEquals(TestSize.SMALL, result)
    }
} 