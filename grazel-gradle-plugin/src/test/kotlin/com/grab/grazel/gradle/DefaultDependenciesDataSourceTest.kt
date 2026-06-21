/*
 * Copyright 2022 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.gradle

import com.android.build.gradle.AppExtension
import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.starlark.BazelDependency
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.dependencies.DefaultDependenciesDataSource
import com.grab.grazel.gradle.dependencies.DefaultDependencyResolutionService
import com.grab.grazel.gradle.dependencies.DependencyResolutionService
import com.grab.grazel.gradle.dependencies.IGNORED_ARTIFACT_GROUPS
import com.grab.grazel.gradle.dependencies.MavenArtifact
import com.grab.grazel.gradle.dependencies.ArtifactsConfig
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.Variant
import com.grab.grazel.gradle.variant.VariantBuilder
import com.grab.grazel.gradle.variant.VariantGraphKey
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.variant.VariantType
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.repositories
import org.gradle.kotlin.dsl.the
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultDependenciesDataSourceTest {
    private lateinit var rootProject: Project
    private lateinit var androidProject: Project
    private lateinit var dependenciesDataSource: DefaultDependenciesDataSource
    private lateinit var dependencyResolutionService: DependencyResolutionService

    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private lateinit var projectDir: File

    fun configure(
        configureProject: Project.() -> Unit = {},
        grazelExtension: GrazelExtension.() -> Unit = {}
    ) {
        projectDir = temporaryFolder.newFolder("projecs")
        rootProject = buildProject("root", projectDir = projectDir).also { root ->
            root.addGrazelExtension(grazelExtension)
        }
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
                namespace = "test"
                compileSdkVersion(30)
            }
            configureProject(this)

            dependencies {
                add(
                    "debugImplementation",
                    "com.android.support:appcompat-v7:28.0.0"
                )
                add(
                    "debugImplementation",
                    "com.android.support:animated-vector-drawable:28.0.0'"
                )
                add(
                    "implementation",
                    "com.google.dagger:dagger:2.37"
                )
            }
        }
        androidProject.doEvaluate()
        rootProject.createGrazelComponent().let { grazelComponent ->
            dependenciesDataSource = grazelComponent
                .dependenciesDataSource()
                .get() as DefaultDependenciesDataSource
            dependencyResolutionService = grazelComponent
                .dependencyResolutionService()
                .get().apply {
                    populateMavenStore(
                        workspaceDependencies = WorkspaceDependencies(
                            variantDeps = buildMap {
                                put(
                                    "debug", listOf(
                                        ResolvedDependency.fromId(
                                            "com.android.support:appcompat-v7:28.0.0",
                                            "debug"
                                        ),
                                        ResolvedDependency.fromId(
                                            "com.android.support:animated-vector-drawable:28.0.0",
                                            "debug"
                                        ),
                                    )
                                )
                            }
                        )
                    )
                }
        }
    }


    @Test
    fun `assert first level module dependencies have default embedded artifacts excluded from them`() {
        configure()
        assertTrue(
            "First level module dependencies does not contain embedded artifacts",
            dependenciesDataSource
                .firstLevelModuleDependencies(androidProject)
                .none { it.moduleGroup in IGNORED_ARTIFACT_GROUPS })
    }

    @Test
    fun `assert dependencyArtifactMap returns artifact and corresponding artifact file`() {
        configure()
        val dependencyArtifactMap = dependenciesDataSource.dependencyArtifactMap(
            rootProject,
            "aar"
        )
        // assert only valid files are returned
        assertTrue("Only valid files are returned") {
            dependencyArtifactMap.values.all {
                it.extension == "aar" && it.exists()
            }
        }
        // assert valid maven coordinates
        assertTrue("Valid maven artifact ids are returned") {
            listOf(
                // We expect force version since dependency resolution happens
                "com.android.support:appcompat-v7:28.0.0",
                "com.android.support:cursoradapter:28.0.0"
            ).all { dep -> dep in dependencyArtifactMap.keys.map(MavenArtifact::toString) }
        }
    }

    @Test
    fun `assert collectMavenDeps with VariantGraphKey returns variant specific classpath`() {
        configure()
        val debugVariant = androidProject.the<AppExtension>()
            .applicationVariants
            .first { it.name == "debug" }!!
        val variantKey = VariantGraphKey.from(
            androidProject,
            debugVariant.name,
            VariantType.AndroidBuild
        )
        val deps = dependenciesDataSource.collectMavenDeps(androidProject, variantKey)
        assertTrue(deps.size == 3, "collectMavenDeps with VariantGraphKey returns variant specific classpath")
    }

    private fun assertCollectMavenDeps(
        grazel: GrazelExtension.() -> Unit = {},
        assertions: (List<BazelDependency>) -> Unit = {}
    ) {
        configure(grazelExtension = grazel)
        val debugVariant = androidProject.the<AppExtension>()
            .applicationVariants
            .first { it.name == "debug" }!!
        val variantKey = VariantGraphKey.from(
            androidProject,
            debugVariant.name,
            VariantType.AndroidBuild
        )
        val deps = dependenciesDataSource.collectMavenDeps(androidProject, variantKey)
        assertEquals(2, deps.size, "collectMavenDeps respects ignore list")
        assertEquals(
            "@debug_maven//:com_android_support_animated_vector_drawable", deps.first().toString()
        )
        assertions(deps)
    }

    @Test
    fun `assert collectMavenDeps respects ignore list`() {
        assertCollectMavenDeps(grazel = {
            dependencies {
                ignoreArtifacts.add("com.android.support:appcompat-v7")
            }
        })
    }

    @Test
    fun `assert collectMavenDeps respects exclude list`() {
        assertCollectMavenDeps(grazel = {
            rules {
                mavenInstall {
                    excludeArtifacts.add("com.android.support:appcompat-v7")
                }
            }
        })
    }

    @Test
    fun `assert Dagger deps are replaced with target`() {
        assertCollectMavenDeps(
            grazel = {
                dependencies {
                    ignoreArtifacts.add("com.android.support:appcompat-v7")
                }
            }, assertions = { deps ->
                assertTrue(deps.any { it.toString() == "//:dagger" })
                assertTrue(deps.none { "com.google.dagger" in it.toString() })
            })
    }

    @Test
    fun `assert auto-service deps are replaced with bundled target`() {
        configure(
            configureProject = {
                dependencies {
                    add("implementation", "com.google.auto.service:auto-service:1.1.1")
                }
            }
        )
        val debugVariant = androidProject.the<AppExtension>()
            .applicationVariants
            .first { it.name == "debug" }!!
        val variantKey = VariantGraphKey.from(
            androidProject,
            debugVariant.name,
            VariantType.AndroidBuild
        )

        val deps = dependenciesDataSource.collectMavenDeps(androidProject, variantKey)

        assertTrue(deps.any { it.toString() == "@grab_bazel_common//third_party/auto-service" })
        assertTrue(deps.none { "com_google_auto_service_auto_service" in it.toString() })
    }

    @Test
    fun `collectMavenDeps keeps auto-service annotation artifacts as Maven labels`() {
        configure(
            configureProject = {
                dependencies {
                    add("implementation", "com.google.auto.service:auto-service-annotations:1.1.1")
                }
            }
        )
        (dependencyResolutionService as DefaultDependencyResolutionService).apply {
            close()
            populateMavenStore(
                WorkspaceDependencies(
                    variantDeps = mapOf(
                        DEFAULT_VARIANT to listOf(
                            ResolvedDependency.fromId(
                                "com.google.auto.service:auto-service-annotations:1.1.1",
                                DEFAULT_VARIANT
                            )
                        ),
                        "debug" to listOf(
                            ResolvedDependency.fromId(
                                "com.android.support:appcompat-v7:28.0.0",
                                "debug"
                            ),
                            ResolvedDependency.fromId(
                                "com.android.support:animated-vector-drawable:28.0.0",
                                "debug"
                            )
                        )
                    )
                )
            )
        }
        val debugVariant = androidProject.the<AppExtension>()
            .applicationVariants
            .first { it.name == "debug" }!!
        val variantKey = VariantGraphKey.from(
            androidProject,
            debugVariant.name,
            VariantType.AndroidBuild
        )

        val deps = dependenciesDataSource.collectMavenDeps(androidProject, variantKey)

        assertTrue(deps.any {
            it.toString() == "@maven//:com_google_auto_service_auto_service_annotations"
        })
    }

    @Test
    fun `collectMavenDeps prefers Gradle-selected child version over stale declared ancestor version`() {
        configure(
            configureProject = {
                dependencies {
                    add("debugImplementation", "com.example:shared:1.0")
                }
            }
        )
        (dependencyResolutionService as DefaultDependencyResolutionService).apply {
            close()
            populateMavenStore(
                WorkspaceDependencies(
                    variantDeps = mapOf(
                        DEFAULT_VARIANT to listOf(
                            ResolvedDependency.fromId("com.example:shared:1.0", DEFAULT_VARIANT)
                        ),
                        "debug" to listOf(
                            ResolvedDependency.fromId(
                                "com.android.support:appcompat-v7:28.0.0",
                                "debug"
                            ),
                            ResolvedDependency.fromId(
                                "com.android.support:animated-vector-drawable:28.0.0",
                                "debug"
                            ),
                            ResolvedDependency.fromId("com.example:shared:2.0", "debug")
                        )
                    )
                )
            )
        }
        val debugVariant = androidProject.the<AppExtension>()
            .applicationVariants
            .first { it.name == "debug" }!!
        val variantKey = VariantGraphKey.from(
            androidProject,
            debugVariant.name,
            VariantType.AndroidBuild
        )

        val deps = dependenciesDataSource.collectMavenDeps(androidProject, variantKey)

        assertTrue(deps.any { it.toString() == "@debug_maven//:com_example_shared" })
    }

    @Test
    fun `collectMavenDeps skips BOM declarations`() {
        configure(
            configureProject = {
                dependencies {
                    add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.1")
                    add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
                }
            }
        )
        (dependencyResolutionService as DefaultDependencyResolutionService).apply {
            close()
            populateMavenStore(
                WorkspaceDependencies(
                    variantDeps = mapOf(
                        "default" to listOf(
                            ResolvedDependency.fromId(
                                "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1",
                                "default"
                            )
                        ),
                        "debug" to listOf(
                            ResolvedDependency.fromId(
                                "com.android.support:appcompat-v7:28.0.0",
                                "debug"
                            ),
                            ResolvedDependency.fromId(
                                "com.android.support:animated-vector-drawable:28.0.0",
                                "debug"
                            )
                        )
                    )
                )
            )
            populateTransitiveDependenciesStore(
                WorkspaceDependencies(
                    variantDeps = emptyMap(),
                    transitiveClasspath = mapOf(
                        "org.jetbrains.kotlinx:kotlinx-coroutines-android" to emptySet()
                    )
                )
            )
        }
        val debugVariant = androidProject.the<AppExtension>()
            .applicationVariants
            .first { it.name == "debug" }!!
        val variantKey = VariantGraphKey.from(
            androidProject,
            debugVariant.name,
            VariantType.AndroidBuild
        )

        val deps = dependenciesDataSource.collectMavenDeps(androidProject, variantKey)
        val transitiveDeps = dependenciesDataSource.collectTransitiveMavenDeps(androidProject, variantKey)

        assertTrue(deps.any { it.toString() == "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_android" })
        assertTrue(deps.none { "kotlinx_coroutines_bom" in it.toString() })
        assertTrue(transitiveDeps.none { it.name.endsWith("-bom") })
    }

    @Test
    fun `collectTransitiveMavenDeps returns direct artifact and full global closure for tags`() {
        configure(
            configureProject = {
                dependencies {
                    add("implementation", "com.example:root:1.0")
                }
            }
        )
        (dependencyResolutionService as DefaultDependencyResolutionService).populateTransitiveDependenciesStore(
            WorkspaceDependencies(
                variantDeps = emptyMap(),
                transitiveClasspath = mapOf(
                    "com.example:root" to setOf(
                        "com.example:child",
                        "com.example:grandchild"
                    )
                )
            )
        )
        val debugVariant = androidProject.the<AppExtension>()
            .applicationVariants
            .first { it.name == "debug" }!!
        val variantKey = VariantGraphKey.from(
            androidProject,
            debugVariant.name,
            VariantType.AndroidBuild
        )

        val transitiveDeps = dependenciesDataSource.collectTransitiveMavenDeps(androidProject, variantKey)
            .mapTo(sortedSetOf()) { it.toString() }

        assertTrue("@maven//:com_example_root" in transitiveDeps)
        assertTrue("@maven//:com_example_child" in transitiveDeps)
        assertTrue("@maven//:com_example_grandchild" in transitiveDeps)
    }

    @Test
    fun `collectMavenDeps uses declaration bucket when leaf resolved classpath also contains artifact`() {
        configure(
            configureProject = {
                configure<AppExtension> {
                    flavorDimensions("tier")
                    productFlavors {
                        create("free") {
                            dimension = "tier"
                        }
                        create("paid") {
                            dimension = "tier"
                        }
                    }
                }
                dependencies {
                    add("freeImplementation", "com.example:shared:1.0")
                }
            }
        )
        (dependencyResolutionService as DefaultDependencyResolutionService).apply {
            close()
            populateMavenStore(
                WorkspaceDependencies(
                    variantDeps = mapOf(
                        "debug" to listOf(
                            ResolvedDependency.fromId(
                                "com.android.support:appcompat-v7:28.0.0",
                                "debug"
                            ),
                            ResolvedDependency.fromId(
                                "com.android.support:animated-vector-drawable:28.0.0",
                                "debug"
                            )
                        ),
                        "free" to listOf(
                            ResolvedDependency.fromId("com.example:shared:1.0", "free")
                        ),
                        "freeDebug" to listOf(
                            ResolvedDependency.fromId("com.example:shared:1.0", "freeDebug")
                        )
                    )
                )
            )
        }
        val freeDebugVariant = androidProject.the<AppExtension>()
            .applicationVariants
            .first { it.name == "freeDebug" }!!
        val variantKey = VariantGraphKey.from(
            androidProject,
            freeDebugVariant.name,
            VariantType.AndroidBuild
        )

        val deps = dependenciesDataSource.collectMavenDeps(androidProject, variantKey)

        assertTrue(deps.any { it.toString() == "@free_maven//:com_example_shared" })
        assertTrue(deps.none { it.toString() == "@freedebug_maven//:com_example_shared" })
    }

    @Test
    fun `collectMavenDeps uses composite declaration bucket for multi flavor source set`() {
        val project = ProjectBuilder.builder().withName("android").build()
        val gpsPaxImplementation = project.configurations.create("gpsPaxImplementation")
        val gpsPaxDebugCompileClasspath = project.configurations
            .create("gpsPaxDebugCompileClasspath")
            .apply {
                extendsFrom(gpsPaxImplementation)
            }
        project.dependencies.add("gpsPaxImplementation", "com.example:combo:1.0")

        val service = object : DefaultDependencyResolutionService() {
            override fun getParameters(): DependencyResolutionService.Params =
                object : DependencyResolutionService.Params {}
        }
        service.populateMavenStore(
            WorkspaceDependencies(
                variantDeps = mapOf(
                    "gpsPax" to listOf(
                        ResolvedDependency.fromId("com.example:combo:1.0", "gpsPax")
                    ),
                    "gps" to listOf(
                        ResolvedDependency.fromId("com.example:combo:1.0", "gps")
                    ),
                    "pax" to listOf(
                        ResolvedDependency.fromId("com.example:combo:1.0", "pax")
                    )
                )
            )
        )
        val variants = setOf(
            fakeVariant(
                project = project,
                name = "gpsPaxDebug",
                extendsFrom = setOf(DEFAULT_VARIANT, "debug", "gps", "pax"),
                variantConfigurations = emptySet(),
                compileConfigurations = setOf(gpsPaxDebugCompileClasspath)
            ),
            fakeVariant(
                project = project,
                name = "gps",
                variantConfigurations = setOf(gpsPaxImplementation)
            ),
            fakeVariant(
                project = project,
                name = "pax",
                variantConfigurations = setOf(gpsPaxImplementation)
            )
        )
        val dependenciesDataSource = DefaultDependenciesDataSource(
            configurationDataSource = object : ConfigurationDataSource {
                override fun resolvedConfigurations(
                    project: Project,
                    vararg variantTypes: VariantType
                ): Sequence<Configuration> = emptySequence()

                override fun configurations(
                    project: Project,
                    vararg variantTypes: VariantType
                ): Sequence<Configuration> = emptySequence()

                override fun isThisConfigurationBelongsToThisVariants(
                    project: Project,
                    vararg variants: com.android.build.gradle.api.BaseVariant?,
                    configuration: Configuration
                ): Boolean = false
            },
            artifactsConfig = ArtifactsConfig(),
            dependencyResolutionService = project.provider { service },
            variantBuilder = object : VariantBuilder {
                override fun build(project: Project): Set<Variant<*>> = variants

                override fun onVariants(project: Project, action: (Variant<*>) -> Unit) {
                    variants.forEach(action)
                }
            }
        )
        val variantKey = VariantGraphKey.from(
            project,
            "gpsPaxDebug",
            VariantType.AndroidBuild
        )

        val deps = dependenciesDataSource.collectMavenDeps(project, variantKey)

        assertEquals(
            listOf("@gps_pax_maven//:com_example_combo"),
            deps.map(BazelDependency::toString)
        )
    }

    @Test
    fun `collectMavenDeps explains versionless declarations missing from resolved workspace`() {
        val root = ProjectBuilder.builder().withName("root").build()
        val project = ProjectBuilder.builder()
            .withName("android")
            .withParent(root)
            .build()
        val implementation = project.configurations.create("implementation")
        val debugCompileClasspath = project.configurations
            .create("debugCompileClasspath")
            .apply {
                extendsFrom(implementation)
            }
        project.dependencies.add(
            "implementation",
            project.dependencies.create(
                mapOf(
                    "group" to "com.example",
                    "name" to "managed"
                )
            )
        )

        val service = object : DefaultDependencyResolutionService() {
            override fun getParameters(): DependencyResolutionService.Params =
                object : DependencyResolutionService.Params {}
        }
        service.populateMavenStore(WorkspaceDependencies(variantDeps = emptyMap()))
        val variants = setOf(
            fakeVariant(
                project = project,
                name = "debug",
                variantConfigurations = setOf(implementation),
                compileConfigurations = setOf(debugCompileClasspath)
            )
        )
        val dependenciesDataSource = DefaultDependenciesDataSource(
            configurationDataSource = object : ConfigurationDataSource {
                override fun resolvedConfigurations(
                    project: Project,
                    vararg variantTypes: VariantType
                ): Sequence<Configuration> = emptySequence()

                override fun configurations(
                    project: Project,
                    vararg variantTypes: VariantType
                ): Sequence<Configuration> = emptySequence()

                override fun isThisConfigurationBelongsToThisVariants(
                    project: Project,
                    vararg variants: com.android.build.gradle.api.BaseVariant?,
                    configuration: Configuration
                ): Boolean = false
            },
            artifactsConfig = ArtifactsConfig(),
            dependencyResolutionService = project.provider { service },
            variantBuilder = object : VariantBuilder {
                override fun build(project: Project): Set<Variant<*>> = variants

                override fun onVariants(project: Project, action: (Variant<*>) -> Unit) {
                    variants.forEach(action)
                }
            }
        )
        val variantKey = VariantGraphKey.from(
            project,
            "debug",
            VariantType.AndroidBuild
        )

        val exception = assertFailsWith<IllegalStateException> {
            dependenciesDataSource.collectMavenDeps(project, variantKey)
        }

        assertTrue(exception.message.orEmpty().contains("versionless"))
        assertTrue(exception.message.orEmpty().contains("com.example:managed"))
        assertTrue(exception.message.orEmpty().contains(":android:implementation"))
    }

    private fun fakeVariant(
        project: Project,
        name: String,
        extendsFrom: Set<String> = setOf(DEFAULT_VARIANT),
        variantConfigurations: Set<Configuration>,
        compileConfigurations: Set<Configuration> = emptySet()
    ): Variant<Any> {
        return object : Variant<Any> {
            override val name: String = name
            override val backingVariant: Any = Any()
            override val project: Project = project
            override val variantType: VariantType = VariantType.AndroidBuild
            override val extendsFrom: Set<String> = extendsFrom
            override val variantConfigurations: Set<Configuration> = variantConfigurations
            override val compileConfiguration: Set<Configuration> = compileConfigurations
            override val runtimeConfiguration: Set<Configuration> = emptySet()
            override val annotationProcessorConfiguration: Set<Configuration> = emptySet()
            override val kspConfiguration: Set<Configuration> = emptySet()
            override val kotlinCompilerPluginConfiguration: Set<Configuration> = emptySet()
        }
    }

    @Test
    fun `collectMavenDeps keeps default declaration on default bucket when exact version is present`() {
        configure(
            configureProject = {
                dependencies {
                    add("implementation", "com.example:shared:1.0")
                }
            }
        )
        (dependencyResolutionService as DefaultDependencyResolutionService).apply {
            close()
            populateMavenStore(
                WorkspaceDependencies(
                    variantDeps = mapOf(
                        "default" to listOf(
                            ResolvedDependency.fromId(
                                "com.android.support:appcompat-v7:28.0.0",
                                "default"
                            ),
                            ResolvedDependency.fromId(
                                "com.android.support:animated-vector-drawable:28.0.0",
                                "default"
                            ),
                            ResolvedDependency.fromId("com.example:shared:1.0", "default")
                        ),
                        "debug" to listOf(
                            ResolvedDependency.fromId(
                                "com.android.support:appcompat-v7:28.0.0",
                                "debug"
                            ),
                            ResolvedDependency.fromId(
                                "com.android.support:animated-vector-drawable:28.0.0",
                                "debug"
                            ),
                            ResolvedDependency.fromId("com.example:shared:1.0", "debug")
                        )
                    )
                )
            )
        }
        val debugVariant = androidProject.the<AppExtension>()
            .applicationVariants
            .first { it.name == "debug" }!!
        val variantKey = VariantGraphKey.from(
            androidProject,
            debugVariant.name,
            VariantType.AndroidBuild
        )

        val deps = dependenciesDataSource.collectMavenDeps(androidProject, variantKey)

        assertTrue(deps.any { it.toString() == "@maven//:com_example_shared" })
        assertTrue(deps.none { it.toString() == "@debug_maven//:com_example_shared" })
    }

    @Test
    fun `collectMavenDeps falls back to selected hierarchy when default declaration has no default owner`() {
        configure(
            configureProject = {
                dependencies {
                    add("implementation", "com.example:shared:1.0")
                }
            }
        )
        (dependencyResolutionService as DefaultDependencyResolutionService).apply {
            close()
            populateMavenStore(
                WorkspaceDependencies(
                    variantDeps = mapOf(
                        "default" to listOf(
                            ResolvedDependency.fromId(
                                "com.android.support:appcompat-v7:28.0.0",
                                "default"
                            ),
                            ResolvedDependency.fromId(
                                "com.android.support:animated-vector-drawable:28.0.0",
                                "default"
                            )
                        ),
                        "debug" to listOf(
                            ResolvedDependency.fromId(
                                "com.android.support:appcompat-v7:28.0.0",
                                "debug"
                            ),
                            ResolvedDependency.fromId(
                                "com.android.support:animated-vector-drawable:28.0.0",
                                "debug"
                            ),
                            ResolvedDependency.fromId("com.example:shared:1.0", "debug")
                        )
                    )
                )
            )
        }
        val debugVariant = androidProject.the<AppExtension>()
            .applicationVariants
            .first { it.name == "debug" }!!
        val variantKey = VariantGraphKey.from(
            androidProject,
            debugVariant.name,
            VariantType.AndroidBuild
        )

        val deps = dependenciesDataSource.collectMavenDeps(androidProject, variantKey)

        assertTrue(deps.any { it.toString() == "@debug_maven//:com_example_shared" })
        assertTrue(deps.none { it.toString() == "@maven//:com_example_shared" })
    }

    @Test
    fun `collectMavenDeps keeps default declarations on default bucket for flavored targets`() {
        configure(
            configureProject = {
                configure<AppExtension> {
                    flavorDimensions("tier")
                    productFlavors {
                        create("free") {
                            dimension = "tier"
                        }
                        create("paid") {
                            dimension = "tier"
                        }
                    }
                }
                dependencies {
                    add("implementation", "com.example:shared:1.0")
                }
            }
        )
        (dependencyResolutionService as DefaultDependencyResolutionService).apply {
            close()
            populateMavenStore(
                WorkspaceDependencies(
                    variantDeps = mapOf(
                        "default" to listOf(
                            ResolvedDependency.fromId(
                                "com.android.support:appcompat-v7:28.0.0",
                                "default"
                            ),
                            ResolvedDependency.fromId(
                                "com.android.support:animated-vector-drawable:28.0.0",
                                "default"
                            ),
                            ResolvedDependency.fromId("com.example:shared:1.0", "default")
                        ),
                        "debug" to listOf(
                            ResolvedDependency.fromId(
                                "com.android.support:appcompat-v7:28.0.0",
                                "debug"
                            ),
                            ResolvedDependency.fromId(
                                "com.android.support:animated-vector-drawable:28.0.0",
                                "debug"
                            ),
                            ResolvedDependency.fromId("com.example:shared:1.0", "debug")
                        ),
                        "freeDebug" to listOf(
                            ResolvedDependency.fromId("com.example:shared:1.0", "freeDebug")
                        )
                    )
                )
            )
        }
        val freeDebugVariant = androidProject.the<AppExtension>()
            .applicationVariants
            .first { it.name == "freeDebug" }!!
        val variantKey = VariantGraphKey.from(
            androidProject,
            freeDebugVariant.name,
            VariantType.AndroidBuild
        )

        val deps = dependenciesDataSource.collectMavenDeps(androidProject, variantKey)

        assertTrue(deps.any { it.toString() == "@maven//:com_example_shared" })
        assertTrue(deps.none { it.toString() == "@debug_maven//:com_example_shared" })
        assertTrue(deps.none { it.toString() == "@free_debug_maven//:com_example_shared" })
    }

    @Test
    fun `assert collectTransitiveMavenDeps with VariantGraphKey returns transitive dependencies`() {
        configure()
        val debugVariant = androidProject.the<AppExtension>()
            .applicationVariants
            .first { it.name == "debug" }!!

        // Mock the dependency resolution service to return transitive dependencies
        (dependencyResolutionService as DefaultDependencyResolutionService).apply {
            populateTransitiveDependenciesStore(
                WorkspaceDependencies(
                    variantDeps = emptyMap(),
                    transitiveClasspath = mapOf(
                        "com.android.support:appcompat-v7" to setOf(
                            "com.android.support:support-v4:28.0.0",
                            "com.android.support:support-annotations:28.0.0"
                        ),
                        "com.google.dagger:dagger" to setOf(
                            "javax.inject:javax.inject:1"
                        )
                    )
                )
            )
        }

        val variantKey = VariantGraphKey.from(
            androidProject,
            debugVariant.name,
            VariantType.AndroidBuild
        )
        val transitiveDeps = dependenciesDataSource.collectTransitiveMavenDeps(
            androidProject,
            variantKey
        )

        // Assert we have the expected transitive dependencies
        assertTrue(
            transitiveDeps.isNotEmpty(),
            "collectTransitiveMavenDeps with VariantGraphKey returns transitive dependencies"
        )

        // Assert specific transitive dependencies are included
        assertTrue(
            transitiveDeps.any { it.group == "com.android.support" && it.name == "support-v4" },
            "Expected transitive dependency com.android.support:support-v4 not found"
        )
        assertTrue(
            transitiveDeps.any { it.group == "javax.inject" && it.name == "javax.inject" },
            "Expected transitive dependency javax.inject:javax.inject not found"
        )
    }
}
