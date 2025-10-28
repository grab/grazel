package com.grab.grazel.migrate.android

import com.android.build.gradle.AppExtension
import com.android.build.gradle.TestExtension
import com.grab.grazel.GrazelExtension
import com.grab.grazel.GrazelPluginTest
import com.grab.grazel.buildProject
import com.grab.grazel.fake.FakeDependencyGraphs
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.gradle.ANDROID_TEST_PLUGIN
import com.grab.grazel.gradle.DefaultConfigurationDataSource
import com.grab.grazel.gradle.KOTLIN_ANDROID_PLUGIN
import com.grab.grazel.gradle.dependencies.ArtifactsConfig
import com.grab.grazel.gradle.dependencies.DefaultDependenciesDataSource
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.DependencyGraphs
import com.grab.grazel.gradle.dependencies.GradleDependencyToBazelDependency
import com.grab.grazel.gradle.variant.DefaultAndroidVariantDataSource
import com.grab.grazel.gradle.variant.DefaultAndroidVariantsExtractor
import com.grab.grazel.gradle.variant.DefaultVariantBuilder
import com.grab.grazel.gradle.variant.DefaultVariantMatcher
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.gradle.variant.VariantMatcher
import com.grab.grazel.migrate.common.TestSizeCalculator
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
    private lateinit var androidTestDataExtractor: DefaultAndroidTestDataExtractor
    private lateinit var variantMatcher: VariantMatcher

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setup() {
        val rootProjectDir = temporaryFolder.newFolder("project")
        rootProject = buildProject("root", projectDir = rootProjectDir)

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

        // Create extractors manually
        val androidVariantsExtractor = DefaultAndroidVariantsExtractor()
        val variantDataSource = DefaultAndroidVariantDataSource(androidVariantsExtractor)
        val configurationDataSource = DefaultConfigurationDataSource(variantDataSource)
        val dependenciesDataSource = DefaultDependenciesDataSource(
            configurationDataSource = configurationDataSource,
            artifactsConfig = ArtifactsConfig(),
            dependencyResolutionService = DefaultDependencyResolutionService.register(rootProject),
            grazelExtension = GrazelExtension(rootProject),
            androidVariantsExtractor = androidVariantsExtractor,
            variantBuilder = DefaultVariantBuilder(variantDataSource),
        )
        val manifestParser = DefaultAndroidManifestParser()
        val keyStoreExtractor = DefaultKeyStoreExtractor()
        val dependencyGraphs = FakeDependencyGraphs()

        // Create dagger.Lazy wrapper
        val dependencyGraphsLazy = object : dagger.Lazy<DependencyGraphs> {
            override fun get() = dependencyGraphs
        }

        val manifestValuesBuilder = DefaultManifestValuesBuilder(
            dependencyGraphsProvider = dependencyGraphsLazy,
            variantDataSource = variantDataSource
        )
        val gradleDependencyToBazelDependency = GradleDependencyToBazelDependency()

        variantMatcher = DefaultVariantMatcher(rootProject, variantDataSource)
        val targetProjectResolver = DefaultTargetProjectResolver(variantMatcher)

        androidTestDataExtractor = DefaultAndroidTestDataExtractor(
            targetProjectResolver = targetProjectResolver,
            dependenciesDataSource = dependenciesDataSource,
            dependencyGraphsProvider = dependencyGraphsLazy,
            gradleDependencyToBazelDependency = gradleDependencyToBazelDependency,
            androidManifestParser = manifestParser,
            manifestValuesBuilder = manifestValuesBuilder,
            keyStoreExtractor = keyStoreExtractor,
            androidVariantDataSource = variantDataSource
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
        val testData = androidTestDataExtractor.extract(testProject, debugVariant())

        assertNotNull(testData)
        assertEquals("app-tests-debug", testData.name)
        assertEquals("com.test.app.tests", testData.customPackage)
        assertEquals("com.test.app", testData.targetPackage)
        assertEquals("androidx.test.runner.AndroidJUnitRunner", testData.testInstrumentationRunner)
    }

    @Test
    fun `extract includes target app library in associates`() {
        val testData = androidTestDataExtractor.extract(testProject, debugVariant())

        // Should include app library (lib_app) in associates (not deps)
        val associateStrings = testData.associates.map { it.toString() }
        assertTrue(associateStrings.any { it.contains("lib_app") },
            "Expected associates to contain app library (lib_app), but got: $associateStrings")
    }

    @Test
    fun `extract excludes target app binary from deps`() {
        val testData = androidTestDataExtractor.extract(testProject, debugVariant())

        // Should NOT include app binary (just :app) in deps
        val depStrings = testData.deps.map { it.toString() }
        assertTrue(depStrings.none { it.matches(Regex("//app:app(?!-)")) },
            "Expected deps to NOT contain app binary (//app:app), but got: $depStrings")
    }

    @Test
    fun `extract sets instruments to app binary`() {
        val testData = androidTestDataExtractor.extract(testProject, debugVariant())

        val instrumentsStr = testData.instruments.toString()
        assertTrue(instrumentsStr.contains("//app:app"),
            "Expected instruments to contain app binary, but got: $instrumentsStr")
    }

    @Test
    fun `extract includes test sources`() {
        val testData = androidTestDataExtractor.extract(testProject, debugVariant())

        assertTrue(testData.srcs.isNotEmpty(), "Expected test sources to be extracted")
        assertTrue(testData.srcs.any { it.contains("ExampleTest.kt") },
            "Expected ExampleTest.kt to be in sources")
    }

    @Test
    fun `extract populates associates field`() {
        val testData = androidTestDataExtractor.extract(testProject, debugVariant())

        assertNotNull(testData.associates)
        assertTrue(testData.associates.isNotEmpty(),
            "Expected associates to be populated")
    }

    @Test
    fun `extract populates resourceFiles field`() {
        val testData = androidTestDataExtractor.extract(testProject, debugVariant())

        // resourceFiles should be populated (may be empty if no resources exist)
        assertNotNull(testData.resourceFiles)
    }

    @Test
    fun `extract populates compose field`() {
        val testData = androidTestDataExtractor.extract(testProject, debugVariant())

        // compose field should be set based on test project's compose configuration
        assertNotNull(testData.compose)
    }

    @Test
    fun `extract uses ManifestValuesBuilder for manifest values`() {
        val testData = androidTestDataExtractor.extract(testProject, debugVariant())

        // manifestValues should be populated by ManifestValuesBuilder
        assertNotNull(testData.manifestValues)
        // Map<String, String?> allows null values
    }
}
