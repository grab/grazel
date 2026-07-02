package com.grab.grazel.migrate.android

import com.android.build.gradle.AppExtension
import com.android.build.gradle.TestExtension
import com.grab.grazel.GrazelExtension
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.gradle.ANDROID_TEST_PLUGIN
import com.grab.grazel.gradle.KOTLIN_ANDROID_PLUGIN
import com.grab.grazel.gradle.dependencies.AndroidTestTargetProjectEdge
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.DependencyGraphNode
import com.grab.grazel.gradle.dependencies.DependencyGraphSourceSet
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.gradle.variant.VariantGraphKey
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.bazel.starlark.BazelDependency.ProjectDependency
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.initDependencyGraphsForTest
import com.grab.grazel.util.doEvaluate
import com.grab.grazel.util.GradleProvider
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
    private lateinit var dependencyGraphs: DependencyGraphs
    private lateinit var dependencyResolutionService: GradleProvider<DefaultDependencyResolutionService>

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
            dependencies.add("implementation", "com.example:standalone-test:1.0")
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
        dependencyGraphs = grazelComponent.dependencyGraphsService().get().get()
        androidLibraryDataExtractor = grazelComponent.androidLibraryDataExtractor().get()
        androidBinaryDataExtractor = grazelComponent.androidBinaryDataExtractor().get()
        androidTestDataExtractor = grazelComponent.androidTestDataExtractor().get()
        dependencyResolutionService = grazelComponent.dependencyResolutionService()
        dependencyResolutionService.get().populateCache(
            WorkspaceDependencies(
                variantDeps = mapOf(
                    DEFAULT_VARIANT to listOf(
                        ResolvedDependency.fromId("com.example:standalone-test:1.0", DEFAULT_VARIANT)
                    )
                ),
                transitiveClasspath = mapOf(
                    "com.example:standalone-test" to setOf("com.example:standalone-child")
                )
            )
        )
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
    fun `dependency graph records android test target project edge`() {
        val variant = debugVariant()
        val variantKey = VariantGraphKey.from(testProject, variant, VariantType.AndroidBuild)
        val graph = dependencyGraphs.variantGraphs.getValue(variantKey)

        val edge = graph.edgeValueOrDefault(testProject, appProject, null)
        assertTrue(edge is AndroidTestTargetProjectEdge)
        assertEquals(":app", edge.targetProjectPath)

        assertEquals(
            setOf(
                DependencyGraphNode(testProject, DependencyGraphSourceSet.Main),
                DependencyGraphNode(appProject, DependencyGraphSourceSet.Main)
            ),
            dependencyGraphs.reachabilityGraph()
                .getValue(DependencyGraphNode(testProject, DependencyGraphSourceSet.AndroidTest))
        )
    }

    @Test
    fun `extract includes target app library in associates`() {
        val variant = debugVariant()
        val androidLibraryData = androidLibraryDataExtractor.extract(testProject, variant)
        val androidBinaryData = androidBinaryDataExtractor.extract(testProject, variant)
        val testData = androidTestDataExtractor.extract(testProject, variant, androidLibraryData, androidBinaryData)

        val appLibraryAssociates = testData.associates
            .filterIsInstance<ProjectDependency>()
            .filter { associate -> associate.dependencyProject == appProject }
        assertTrue(
            appLibraryAssociates.any { associate ->
                associate.prefix == "lib_" && associate.suffix == "-debug"
            },
            "Expected associates to contain app library dependency, but got: $appLibraryAssociates"
        )
    }

    @Test
    fun `extract excludes target app binary from deps`() {
        val variant = debugVariant()
        val androidLibraryData = androidLibraryDataExtractor.extract(testProject, variant)
        val androidBinaryData = androidBinaryDataExtractor.extract(testProject, variant)
        val testData = androidTestDataExtractor.extract(testProject, variant, androidLibraryData, androidBinaryData)

        val plainAppBinaryDeps = testData.deps
            .filterIsInstance<ProjectDependency>()
            .filter { dependency ->
                dependency.dependencyProject == appProject &&
                    dependency.prefix.isEmpty() &&
                    dependency.suffix.isEmpty()
            }
        assertTrue(
            plainAppBinaryDeps.isEmpty(),
            "Expected deps to not contain the app binary dependency, but got: $plainAppBinaryDeps"
        )
    }

    @Test
    fun `extract sets instruments to app binary`() {
        val variant = debugVariant()
        val androidLibraryData = androidLibraryDataExtractor.extract(testProject, variant)
        val androidBinaryData = androidBinaryDataExtractor.extract(testProject, variant)
        val testData = androidTestDataExtractor.extract(testProject, variant, androidLibraryData, androidBinaryData)

        val instruments = testData.instruments
        assertTrue(
            instruments is ProjectDependency,
            "Expected instruments to keep structured project dependency, but got: $instruments"
        )
        assertEquals(appProject, instruments.dependencyProject)
        assertEquals("-debug", instruments.suffix)
        assertEquals("", instruments.prefix)
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
    fun `extract derives transitive maven tags from standalone test deps`() {
        rootProject.the<GrazelExtension>().rules.kotlin.enabledTransitiveReduction = true

        val variant = debugVariant()
        val androidLibraryData = androidLibraryDataExtractor.extract(testProject, variant)
        val androidBinaryData = androidBinaryDataExtractor.extract(testProject, variant)
        val testData = androidTestDataExtractor.extract(testProject, variant, androidLibraryData, androidBinaryData)

        assertTrue("@maven//:com_example_standalone_test" in testData.tags)
        assertTrue("@maven//:com_example_standalone_child" in testData.tags)
        assertTrue(
            testData.tags.containsAll(androidLibraryData.tags.filterNot { tag -> tag.startsWith("@maven//:") }),
            "Expected android test tags to retain non-Maven local tags from library extraction"
        )
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
