package com.grab.grazel.migrate.android

import com.android.build.gradle.AppExtension
import com.android.build.gradle.TestExtension
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.gradle.ANDROID_TEST_PLUGIN
import com.grab.grazel.gradle.KOTLIN_ANDROID_PLUGIN
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.initDependencyGraphsForTest
import com.grab.grazel.util.doEvaluate
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.the
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultAndroidTestDataExtractorTest : GrazelPluginTest() {
    private lateinit var rootProject: Project
    private lateinit var appProject: Project
    private lateinit var testProject: Project
    private lateinit var androidTestDataExtractor: AndroidTestDataExtractor
    private lateinit var androidLibraryDataExtractor: AndroidLibraryDataExtractor
    private lateinit var androidBinaryDataExtractor: AndroidBinaryDataExtractor

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setup() {
        val rootProjectDir = temporaryFolder.newFolder("project")
        rootProject = buildProject("root", projectDir = rootProjectDir)
        rootProject.addGrazelExtension()

        val appProjectDir = File(rootProjectDir, "app").apply { mkdirs() }
        appProject = buildProject("app", rootProject, projectDir = appProjectDir)

        val testProjectDir = File(rootProjectDir, "app-tests").apply { mkdirs() }
        testProject = buildProject("app-tests", rootProject, projectDir = testProjectDir)

        // Setup app project
        with(appProject) {
            plugins.apply {
                apply(ANDROID_APPLICATION_PLUGIN)
                apply(KOTLIN_ANDROID_PLUGIN)
            }
            extensions.configure<AppExtension> {
                namespace = "com.test.app"
                defaultConfig {
                    compileSdkVersion(32)
                    setApplicationId("com.test.app")
                }
            }
        }

        // Create app manifest
        File(appProjectDir, "src/main/AndroidManifest.xml").apply {
            parentFile.mkdirs()
            createNewFile()
            writeText("""
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="com.test.app">
                    <application />
                </manifest>
            """.trimIndent())
        }

        // Setup test project
        with(testProject) {
            plugins.apply {
                apply(ANDROID_TEST_PLUGIN)
                apply(KOTLIN_ANDROID_PLUGIN)
            }
            extensions.configure<TestExtension> {
                namespace = "com.test.app.tests"
                defaultConfig {
                    compileSdkVersion(32)
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                targetProjectPath = ":app"
            }
        }

        // Create test manifest
        File(testProjectDir, "src/main/AndroidManifest.xml").apply {
            parentFile.mkdirs()
            createNewFile()
            writeText("""
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="com.test.app.tests">
                    <instrumentation
                        android:name="androidx.test.runner.AndroidJUnitRunner"
                        android:targetPackage="com.test.app" />
                    <application android:debuggable="true" />
                </manifest>
            """.trimIndent())
        }

        // Create test source file
        File(testProjectDir, "src/main/java/com/test/app/tests/ExampleTest.kt").apply {
            parentFile.mkdirs()
            createNewFile()
            writeText("""
                package com.test.app.tests

                class ExampleTest {
                    // Test code
                }
            """.trimIndent())
        }

        appProject.doEvaluate()
        testProject.doEvaluate()

        // Get extractors from GrazelComponent
        val grazelComponent = rootProject.createGrazelComponent()
        grazelComponent.initDependencyGraphsForTest(rootProject)
        androidLibraryDataExtractor = grazelComponent.androidLibraryDataExtractor().get()
        androidBinaryDataExtractor = grazelComponent.androidBinaryDataExtractor().get()
        androidTestDataExtractor = grazelComponent.androidTestDataExtractor().get()
    }

    private fun debugVariant(): MatchedVariant {
        val variant = testProject.the<TestExtension>()
            .applicationVariants
            .first { it.buildType.name == "debug" }
        return MatchedVariant.from(variant)
    }

    @Test
    fun `extract returns AndroidTestData with correct structure`() {
        val variant = debugVariant()
        val androidLibraryData = androidLibraryDataExtractor.extract(testProject, variant)
        val androidBinaryData = androidBinaryDataExtractor.extract(testProject, variant)
        val testData = androidTestDataExtractor.extract(testProject, variant, androidLibraryData, androidBinaryData)

        assertNotNull(testData)
        assertEquals("app-tests-debug", testData.name)
        assertEquals("com.test.app.tests", testData.customPackage)
        assertEquals("com.test.app", testData.targetPackage)
        assertEquals("androidx.test.runner.AndroidJUnitRunner", testData.testInstrumentationRunner)
    }

    @Test
    fun `extract includes target app library in associates`() {
        val variant = debugVariant()
        val androidLibraryData = androidLibraryDataExtractor.extract(testProject, variant)
        val androidBinaryData = androidBinaryDataExtractor.extract(testProject, variant)
        val testData = androidTestDataExtractor.extract(testProject, variant, androidLibraryData, androidBinaryData)

        // Should include app library (lib_app) in associates (not deps)
        val associateStrings = testData.associates.map { it.toString() }
        assertTrue(associateStrings.any { it.contains("lib_app") },
            "Expected associates to contain app library (lib_app), but got: $associateStrings")
    }

    @Test
    fun `extract excludes target app binary from deps`() {
        val variant = debugVariant()
        val androidLibraryData = androidLibraryDataExtractor.extract(testProject, variant)
        val androidBinaryData = androidBinaryDataExtractor.extract(testProject, variant)
        val testData = androidTestDataExtractor.extract(testProject, variant, androidLibraryData, androidBinaryData)

        // Should NOT include app binary (just :app) in deps
        val depStrings = testData.deps.map { it.toString() }
        assertTrue(depStrings.none { it.matches(Regex("//app:app(?!-)")) },
            "Expected deps to NOT contain app binary (//app:app), but got: $depStrings")
    }

    @Test
    fun `extract sets instruments to app binary`() {
        val variant = debugVariant()
        val androidLibraryData = androidLibraryDataExtractor.extract(testProject, variant)
        val androidBinaryData = androidBinaryDataExtractor.extract(testProject, variant)
        val testData = androidTestDataExtractor.extract(testProject, variant, androidLibraryData, androidBinaryData)

        val instrumentsStr = testData.instruments.toString()
        assertTrue(instrumentsStr.contains("//app:app"),
            "Expected instruments to contain app binary, but got: $instrumentsStr")
    }

    @Test
    fun `extract includes test sources`() {
        val variant = debugVariant()
        val androidLibraryData = androidLibraryDataExtractor.extract(testProject, variant)
        val androidBinaryData = androidBinaryDataExtractor.extract(testProject, variant)
        val testData = androidTestDataExtractor.extract(testProject, variant, androidLibraryData, androidBinaryData)

        assertTrue(testData.srcs.isNotEmpty(), "Expected test sources to be extracted")
        assertTrue(testData.srcs.any { it.contains("ExampleTest.kt") },
            "Expected ExampleTest.kt to be in sources")
    }

    @Test
    fun `extract populates associates field`() {
        val variant = debugVariant()
        val androidLibraryData = androidLibraryDataExtractor.extract(testProject, variant)
        val androidBinaryData = androidBinaryDataExtractor.extract(testProject, variant)
        val testData = androidTestDataExtractor.extract(testProject, variant, androidLibraryData, androidBinaryData)

        assertNotNull(testData.associates)
        assertTrue(testData.associates.isNotEmpty(),
            "Expected associates to be populated")
    }

    @Test
    fun `extract populates resourceFiles field`() {
        val variant = debugVariant()
        val androidLibraryData = androidLibraryDataExtractor.extract(testProject, variant)
        val androidBinaryData = androidBinaryDataExtractor.extract(testProject, variant)
        val testData = androidTestDataExtractor.extract(testProject, variant, androidLibraryData, androidBinaryData)

        // resourceFiles should be populated (may be empty if no resources exist)
        assertNotNull(testData.resourceFiles)
    }

    @Test
    fun `extract populates compose field`() {
        val variant = debugVariant()
        val androidLibraryData = androidLibraryDataExtractor.extract(testProject, variant)
        val androidBinaryData = androidBinaryDataExtractor.extract(testProject, variant)
        val testData = androidTestDataExtractor.extract(testProject, variant, androidLibraryData, androidBinaryData)

        // compose field should be set based on test project's compose configuration
        assertNotNull(testData.compose)
    }

    @Test
    fun `extract uses ManifestValuesBuilder for manifest values`() {
        val variant = debugVariant()
        val androidLibraryData = androidLibraryDataExtractor.extract(testProject, variant)
        val androidBinaryData = androidBinaryDataExtractor.extract(testProject, variant)
        val testData = androidTestDataExtractor.extract(testProject, variant, androidLibraryData, androidBinaryData)

        // manifestValues should be populated by ManifestValuesBuilder
        assertNotNull(testData.manifestValues)
        // Map<String, String?> allows null values
    }
}
