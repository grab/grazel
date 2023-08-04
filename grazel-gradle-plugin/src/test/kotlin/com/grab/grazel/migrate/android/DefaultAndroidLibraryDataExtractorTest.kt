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
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.the
import org.junit.Test
import kotlin.test.assertEquals

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
        libraryProject = buildProject("lib", rootProject)
        with(appProject) {
            with(plugins) {
                apply(ANDROID_APPLICATION_PLUGIN)
                apply(KOTLIN_ANDROID_PLUGIN)
                apply(KOTLIN_KAPT)
            }
            configure<AppExtension> {
                defaultConfig {
                    compileSdkVersion(32)
                }
                app(this)
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

        dependencyResolutionService.get().populateCache(
            workspaceDependencies = WorkspaceDependencies(
                result = buildMap {
                    put(
                        "maven", listOf(
                            from("com.android.databinding:viewbinding:1.0.0:maven"),
                            from("com.android.databinding:baseLibrary:1.0.0:maven")
                        )
                    )
                }
            ))
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
}