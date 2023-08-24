package com.grab.grazel.migrate.dependencies

import com.grab.grazel.gradle.Repository
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class UrlRewriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private lateinit var outputFile: File

    @Before
    fun setup() {
        outputFile = temporaryFolder.newFile("output.cfg")
    }

    @Test
    fun `assert rewriter config is only generated for private repositories with username and password`() {
        val urlRewriter = UrlRewriter()
        urlRewriter.generate(
            outputFile,
            setOf(
                Repository(
                    name = "maven",
                    url = "https://maven.com",
                    username = null,
                    password = null
                ),
                Repository(
                    name = "maven",
                    url = "https://maven.com",
                    username = null,
                    password = ""
                ),
                Repository(
                    name = "maven",
                    url = "https://maven.com",
                    username = "x",
                    password = "y"
                )
            )
        )
        outputFile.readLines().let { lines ->
            assertTrue(lines.size == 1, "Only private repos are considered")
            assert("rewrite" in lines.first())
        }
    }

    @Test
    fun `assert rewriter config rewrites url to basic auth url`() {
        val urlRewriter = UrlRewriter()
        urlRewriter.generate(
            outputFile,
            setOf(
                Repository(
                    name = "maven",
                    url = "https://maven.com",
                    username = "x",
                    password = "y"
                ),
                Repository(
                    name = "maven",
                    url = "https://maven.com",
                    username = "user",
                    password = "password"
                )
            )
        )
        val content = outputFile.readLines()
        assertEquals("rewrite maven.com/(.*) user:password@maven.com/\$1", content[0])
        assertEquals("rewrite maven.com/(.*) x:y@maven.com/\$1", content[1])
    }

    @Test
    fun `assert rewriter config throws error with malformed url`() {
        val urlRewriter = UrlRewriter()
        assertFails("Illegal character in path at index 9: malformed url") {
            urlRewriter.generate(
                outputFile,
                setOf(
                    Repository(
                        name = "maven",
                        url = "malformed url",
                        username = "x",
                        password = "y"
                    ),
                )
            )
        }
    }
}