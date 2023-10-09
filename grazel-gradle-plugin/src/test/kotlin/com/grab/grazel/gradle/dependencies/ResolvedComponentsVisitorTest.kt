package com.grab.grazel.gradle.dependencies

import com.android.build.gradle.AppExtension
import com.grab.grazel.buildProject
import com.grab.grazel.fake.FakeAttributeContainer
import com.grab.grazel.fake.fakeComponentResult
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import com.grab.grazel.util.truth
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.result.DefaultResolvedDependencyResult
import org.gradle.api.internal.artifacts.result.DefaultResolvedVariantResult
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.repositories
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertTrue

class ResolvedComponentsVisitorTest {
    private lateinit var rootProject: Project
    private lateinit var androidProject: Project
    private lateinit var variantBuilder: VariantBuilder
    private lateinit var compileConfigurations: Set<Configuration>

    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private lateinit var projectDir: File

    @Before
    fun setup() {
        projectDir = temporaryFolder.newFolder("project")
        rootProject = buildProject(
            "root",
            projectDir = projectDir,
        ).also { root -> root.addGrazelExtension() }
        setupAndroidProject()
        val grazelComponent = rootProject.createGrazelComponent()
        variantBuilder = grazelComponent.variantBuilder().get()
        compileConfigurations = variantBuilder.build(androidProject)
            .asSequence()
            .filter { it.variantType == VariantType.AndroidBuild }
            .first()
            .compileConfiguration
    }

    private fun setupAndroidProject() {
        androidProject = buildProject("android", rootProject)
        with(androidProject) {
            with(plugins) {
                apply(ANDROID_APPLICATION_PLUGIN)
            }
            repositories {
                google()
                mavenCentral()
            }
            configure<AppExtension> {
                compileSdkVersion(30)
            }
            dependencies {
                add(
                    "implementation",
                    "com.google.dagger:dagger:2.37"
                )
                add(
                    "implementation",
                    "com.google.android.support:wearable:2.1.0"
                )
                add(
                    "implementation",
                    "com.google.ar.sceneform.ux:sceneform-ux:1.0.0"
                )
            }
        }
        androidProject.doEvaluate()
    }

    @Test
    fun `assert jetifier is enabled when legacy support lib is used`() {
        val results = compileConfigurations.flatMap { configuration ->
            ResolvedComponentsVisitor().visit(configuration.incoming.resolutionResult.root) { it }
        }
        assertTrue("Artifacts containing support libs are jetified") {
            results
                .asSequence()
                .filter { it.requiresJetifier }
                .all {
                    val id = it.component.toString()
                    id.startsWith("com.android.support") ||
                        id.startsWith("com.google.ar") ||
                        id.startsWith("com.google.android")
                }
        }
    }

    @Test
    fun `assert jetifier sources is calculated for androidx artifacts`() {
        // Construct the graph manually since we can't inject android.useAndroidX=true in test.
        // Without that we can't have androidx dependencies in ProjectBuilder.
        // Related: https://github.com/gradle/gradle/issues/17638
        // The other alternative is to write functionalTest which can be slow.

        val rootComponent = fakeComponentResult {
            val percentLayoutComponent = fakeComponentResult(
                group = "androidx.percentlayout",
                name = "percentlayout",
                version = "1.0.0",
                isProject = false
            )
            val percentIdentifier = DefaultModuleIdentifier.newId(
                /* group = */"androidx.percentlayout",
                /* name = */"percentlayout"
            )
            addDependency(
                DefaultResolvedDependencyResult(
                    /* requested = */ DefaultModuleComponentSelector
                        .newSelector(/* id = */ percentIdentifier, /* version = */ "1.0.0"),
                    /* constraint = */ false,
                    /* selectedComponent = */ percentLayoutComponent,
                    /* selectedVariant = */ DefaultResolvedVariantResult(
                        /* owner = */ DefaultModuleComponentIdentifier
                            .newId(percentIdentifier, "1.0.0"),
                        /* displayName = */ object : DisplayName {
                            override fun getDisplayName(): String = ""
                            override fun getCapitalizedDisplayName(): String = ""
                        },
                        /* attributes = */ FakeAttributeContainer(),
                        /* capabilities = */ emptyList(),
                        /* externalVariant = */ null
                    ),
                    /* from = */ percentLayoutComponent
                )
            )
        }
        val results = ResolvedComponentsVisitor().visit(rootComponent) { it }

        assertTrue("Visit collects transitive deps when jetifier artifacts are present") {
            results.isNotEmpty() && results.last().transitiveDeps.isNotEmpty()
        }

        assertTrue("Transitive deps contain unjetifiedSource") {
            results.last().transitiveDeps.first().unjetifiedSource == "com.android.support:percent"
        }
    }

    @Test
    fun `assert resolved component visitor flattens the graph`() {
        val results = compileConfigurations.flatMap { configuration ->
            ResolvedComponentsVisitor().visit(configuration.incoming.resolutionResult.root) { it }
        }
        assertTrue("Resolved component visitor flattens the transitive dependencies") {
            results.size == 18
        }
        // Tests for sorting and jetifier
        results.flatMapTo(HashSet()) { visitResult ->
            visitResult.transitiveDeps.map { it.dependency.toString() }
        }.truth {
            containsExactly(
                "com.android.support:support-compat:26.0.2",
                "com.android.support:support-media-compat:26.0.2",
                "javax.inject:javax.inject:1",
                "com.android.support:recyclerview-v7:26.0.2",
                "com.google.ar:core:1.2.0",
                "com.google.ar.sceneform:filament-android:1.0.0",
                "com.android.support:support-annotations:26.0.2",
                "com.android.support:support-v4:26.0.2",
                "com.google.ar.sceneform:rendering:1.0.0",
                "com.google.ar.sceneform:sceneform-base:1.0.0",
                "com.android.support:percent:26.0.2",
                "com.android.support:support-fragment:26.0.2",
                "com.google.ar.sceneform:core:1.0.0",
                "com.android.support:support-core-ui:26.0.2",
                "com.android.support:support-core-utils:26.0.2"
            )
        }
    }
}