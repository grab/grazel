package com.grab.grazel.migrate.android

import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.gradle.ANDROID_LIBRARY_PLUGIN
import com.grab.grazel.gradle.KOTLIN_ANDROID_PLUGIN
import com.grab.grazel.gradle.KOTLIN_KAPT
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency.Companion.from
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.variant.MatchedVariant
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
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
    private lateinit var androidLibraryDataExtractor: AndroidLibraryDataExtractor

    private fun configure(
        app: AppExtension.() -> Unit = {},
        lib: LibraryExtension.() -> Unit = {}
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
        }

        libraryProject.doEvaluate()
        appProject.doEvaluate()
        val grazelComponent = rootProject.createGrazelComponent()
        dependencyResolutionService = grazelComponent.dependencyResolutionService()
        androidLibraryDataExtractor = grazelComponent.androidLibraryDataExtractor().get()

        dependencyResolutionService.get().populateMavenStore(
            workspaceDependencies = WorkspaceDependencies(
                result = buildMap {
                    put(
                        "maven", listOf(
                            from("com.android.databinding:viewbinding:1.0.0:maven:false:null"),
                            from("com.android.databinding:baseLibrary:1.0.0:maven:false:null")
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
}