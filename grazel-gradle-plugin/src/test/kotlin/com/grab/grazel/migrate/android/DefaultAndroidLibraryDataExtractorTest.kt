package com.grab.grazel.migrate.android

import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.gradle.ANDROID_LIBRARY_PLUGIN
import com.grab.grazel.gradle.KOTLIN_ANDROID_PLUGIN
import com.grab.grazel.gradle.KOTLIN_KAPT
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency.Companion.fromId
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import com.grab.grazel.util.initDependencyGraphsForTest
import com.grab.grazel.util.truth
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.the
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DefaultAndroidLibraryDataExtractorTest {
    private lateinit var rootProject: Project
    private lateinit var appProject: Project
    private lateinit var libraryProject: Project
    private lateinit var dependencyResolutionService: Provider<DefaultDependencyResolutionService>
    private lateinit var grazelComponent: com.grab.grazel.di.GrazelComponent
    private lateinit var androidLibraryDataExtractor: AndroidLibraryDataExtractor

    private fun configure(
        app: AppExtension.() -> Unit = {},
        lib: LibraryExtension.() -> Unit = {},
        appDependencies: Project.() -> Unit = {},
        libraryDependencies: Project.() -> Unit = {}
    ) {
        rootProject = buildProject("root").also {
            it.addGrazelExtension()
        }
        appProject = buildProject("android", rootProject)
        createSources()
        libraryProject = buildProject("lib", rootProject)
        with(appProject) {
            with(plugins) {
                apply(ANDROID_APPLICATION_PLUGIN)
                apply(KOTLIN_ANDROID_PLUGIN)
                apply(KOTLIN_KAPT)
            }
            configure<AppExtension> {
                namespace = "test"
                defaultConfig {
                    compileSdkVersion(32)
                }
                app(this)
                sourceSets.named("main").configure {
                    res.srcDirs("src/main/res-extra")
                }
            }
            dependencies {
                add("implementation", libraryProject)
            }
            appDependencies()
        }
        with(libraryProject) {
            with(plugins) {
                apply(ANDROID_LIBRARY_PLUGIN)
                apply(KOTLIN_ANDROID_PLUGIN)
                apply(KOTLIN_KAPT)
            }
            configure<LibraryExtension> {
                namespace = "test"
                defaultConfig {
                    compileSdkVersion(32)
                }
                lib(this)
            }
            libraryDependencies()
        }

        libraryProject.doEvaluate()
        appProject.doEvaluate()
        grazelComponent = rootProject.createGrazelComponent()
        grazelComponent.initDependencyGraphsForTest(rootProject)
        dependencyResolutionService = grazelComponent.dependencyResolutionService()
        androidLibraryDataExtractor = grazelComponent.androidLibraryDataExtractor().get()

        dependencyResolutionService.get().populateCache(
            workspaceDependencies = WorkspaceDependencies(
                variantDeps = buildMap {
                    put(
                        DEFAULT_VARIANT, listOf(
                            fromId("com.android.databinding:viewbinding:1.0.0", "maven"),
                            fromId("com.android.databinding:baseLibrary:1.0.0", "maven"),
                            fromId("com.android.databinding:library:1.0.0", "maven"),
                            fromId("com.android.databinding:adapters:1.0.0", "maven")
                        )
                    )
                }
            ))
    }

    private fun createSources() {
        appProject.file("src/main/res/values")
            .toPath()
            .also(Files::createDirectories)
            .resolve("values.xml")
            .writeText("")
        appProject.file("src/debug")
            .toPath()
            .also(Files::createDirectories)
            .resolve("AndroidManifest.xml")
            .writeText("<manifest package=\"grazel\" />")
        appProject.file("src/main/res-extra/values")
            .toPath()
            .also(Files::createDirectories)
            .resolve("values.xml")
            .writeText("")
    }

    private fun debugVariant(): MatchedVariant {
        val variant = appProject.the<AppExtension>()
            .applicationVariants
            .first { it.buildType.name == "debug" }
        return MatchedVariant.from(variant)
    }


    @Test
    fun `assert viewbinding or databinding is extracted as databinding flag`() {
        configure(
            app = {
                dataBinding.isEnabled = false
            }
        )
        assertEquals(
            false,
            androidLibraryDataExtractor.extract(appProject, debugVariant()).databinding
        )
        configure(
            app = {
                dataBinding.isEnabled = true
            }
        )
        assertEquals(
            true,
            androidLibraryDataExtractor.extract(appProject, debugVariant()).databinding
        )
        configure(
            app = {
                buildFeatures.viewBinding = true
            }
        )
        assertEquals(
            true,
            androidLibraryDataExtractor.extract(appProject, debugVariant()).databinding
        )
    }

    @Test
    fun `assert resource sets are calculated correctly for variants`() {
        configure()
        val resourceSets = androidLibraryDataExtractor
            .extract(appProject, debugVariant())
            .resourceSets
        resourceSets.truth {
            containsExactly(
                BazelSourceSet(
                    name = "debug",
                    res = null,
                    assets = null,
                    manifest = "src/debug/AndroidManifest.xml"
                ),
                BazelSourceSet(
                    name = "main",
                    res = "src/main/res-extra",
                    assets = null,
                    manifest = null,
                ),
                BazelSourceSet(
                    name = "main",
                    res = "src/main/res",
                    assets = null,
                    manifest = null
                )
            )
            containsNoDuplicates()
            hasSize(3)
        }
    }

    @Test
    fun `assert sources in build directory are filtered out`() {
        configure()

        // Create normal source file
        appProject.file("src/main/java/com/example/Sample.kt").apply {
            parentFile.mkdirs()
            writeText("class Sample")
        }

        // Create source file in build/ directory
        appProject.file("build/generated/source/kapt/debug/com/example/Generated.kt").apply {
            parentFile.mkdirs()
            writeText("class Generated")
        }

        val androidLibraryData = androidLibraryDataExtractor.extract(appProject, debugVariant())

        // Assert that sources don't contain any path starting with "build/"
        androidLibraryData.srcs.forEach { src ->
            assertFalse(
                src.startsWith("build/"),
                "Expected no sources to start with 'build/' but found: $src"
            )
        }
    }

    @Test
    fun `assert resources in build directory are filtered out`() {
        configure()

        // Create resource file in build/ directory
        appProject.file("build/generated/res/resValues/debug/values/generated.xml").apply {
            parentFile.mkdirs()
            writeText("<resources></resources>")
        }

        val androidLibraryData = androidLibraryDataExtractor.extract(appProject, debugVariant())

        // Assert that no resource set has paths starting with "build/"
        androidLibraryData.resourceSets.forEach { resourceSet ->
            resourceSet.res?.let { res ->
                assertFalse(
                    res.startsWith("build/"),
                    "Expected resource path not to start with 'build/' but found: $res"
                )
            }
            resourceSet.assets?.let { assets ->
                assertFalse(
                    assets.startsWith("build/"),
                    "Expected assets path not to start with 'build/' but found: $assets"
                )
            }
            resourceSet.manifest?.let { manifest ->
                assertFalse(
                    manifest.startsWith("build/"),
                    "Expected manifest path not to start with 'build/' but found: $manifest"
                )
            }
        }
    }

    @Test
    fun `extract derives transitive maven tag labels from own direct maven deps only`() {
        configure(
            appDependencies = {
                dependencies {
                    add("implementation", "com.example:root:1.0")
                }
            },
            libraryDependencies = {
                dependencies {
                    add("implementation", "com.example:donated:1.0")
                }
            }
        )
        rootProject.the<com.grab.grazel.GrazelExtension>()
            .rules.kotlin.enabledTransitiveReduction = true
        dependencyResolutionService.get().close()
        dependencyResolutionService.get().populateCache(
            WorkspaceDependencies(
                variantDeps = mapOf(
                    DEFAULT_VARIANT to listOf(
                        fromId("com.example:root:1.0", DEFAULT_VARIANT),
                        fromId("com.example:child:1.0", DEFAULT_VARIANT)
                    )
                ),
                transitiveClasspath = mapOf(
                    "com.example:root" to setOf("com.example:child"),
                    "com.example:donated" to setOf("com.example:donated-child")
                )
            )
        )

        val androidLibraryData = androidLibraryDataExtractor.extract(appProject, debugVariant())

        androidLibraryData.tags.truth {
            contains("@maven//:com_example_root")
            contains("@maven//:com_example_child")
            contains("@self//android")
            doesNotContain("@maven//:com_example_donated")
            doesNotContain("@maven//:com_example_donated_child")
        }
    }

    @Test
    fun `extract derives maven tag labels from live Gradle declarations before dependency filtering`() {
        configure(
            app = {
                dataBinding.isEnabled = true
            },
            appDependencies = {
                dependencies {
                    add("implementation", "com.stepango.rxdatabindings:rxdatabindings:2.0.0")
                    add("implementation", "androidx.databinding:databinding-runtime:8.6.1")
                }
            }
        )
        rootProject.the<com.grab.grazel.GrazelExtension>()
            .rules.kotlin.enabledTransitiveReduction = true
        dependencyResolutionService.get().close()
        dependencyResolutionService.get().populateCache(
            WorkspaceDependencies(
                variantDeps = mapOf(
                    DEFAULT_VARIANT to listOf(
                        fromId("com.stepango.rxdatabindings:rxdatabindings:2.0.0", DEFAULT_VARIANT),
                        fromId("androidx.databinding:databinding-runtime:8.6.1", DEFAULT_VARIANT),
                        fromId("androidx.databinding:databinding-common:8.6.1", DEFAULT_VARIANT)
                    )
                ),
                transitiveClasspath = mapOf(
                    "androidx.databinding:databinding-runtime" to setOf(
                        "androidx.databinding:databinding-common"
                    )
                )
            )
        )

        val androidLibraryData = androidLibraryDataExtractor.extract(appProject, debugVariant())

        androidLibraryData.deps.map { it.toString() }.truth {
            contains("@maven//:com_stepango_rxdatabindings_rxdatabindings")
            doesNotContain("@maven//:androidx_databinding_databinding_runtime")
            doesNotContain("@maven//:androidx_databinding_databinding_common")
        }
        androidLibraryData.tags.truth {
            contains("@maven//:com_stepango_rxdatabindings_rxdatabindings")
            contains("@maven//:androidx_databinding_databinding_runtime")
            contains("@maven//:androidx_databinding_databinding_common")
        }
    }
}
