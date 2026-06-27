package com.grab.grazel.migrate.dependencies

import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.rules.MavenInstallArtifact.DetailedArtifact
import com.grab.grazel.bazel.rules.MavenInstallArtifact.Exclusion.SimpleExclusion
import com.grab.grazel.bazel.rules.MavenRepository.DefaultMavenRepository
import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.dependencies.ComputeWorkspaceDependencies
import com.grab.grazel.gradle.dependencies.DECLARED_DEPENDENCY_REPOSITORY
import com.grab.grazel.gradle.dependencies.model.OverrideTarget
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.COMPILE
import com.grab.grazel.gradle.dependencies.model.ResolveDependenciesResult.Companion.Scope.KSP
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.gradle.variant.LINT_VARIANT
import com.grab.grazel.gradle.variant.setupAndroidVariantProject
import com.grab.grazel.gradle.variant.setupJvmVariantProject
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import org.gradle.api.Project
import org.gradle.api.file.ProjectLayout
import org.gradle.kotlin.dsl.repositories
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MavenInstallArtifactsCalculatorTest {
    private lateinit var rootProject: Project
    private lateinit var androidProject: Project
    private lateinit var jvmProject: Project
    private lateinit var mavenInstallArtifactsCalculator: MavenInstallArtifactsCalculator

    private fun setup(configure: GrazelExtension.() -> Unit = {}) {
        rootProject = buildProject("root")
        rootProject.addGrazelExtension(configure)

        androidProject = buildProject("android", rootProject)
        setupAndroidVariantProject(androidProject)
        androidProject.repositories { mavenCentral() }

        jvmProject = buildProject("java", rootProject)
        setupJvmVariantProject(jvmProject)

        listOf(rootProject, androidProject, jvmProject).forEach { it.doEvaluate() }

        val grazelComponent = rootProject.createGrazelComponent()
        mavenInstallArtifactsCalculator = grazelComponent.mavenInstallArtifactsCalculator().get()
    }

    @Test
    fun `test jetifyExcludeList should remove artifacts from jetifier list`() {
        setup {
            rules {
                mavenInstall {
                    jetifyExcludeList.set(
                        listOf(
                            "androidx.core:core",
                            "com.google.android:material"
                        )
                    )
                }
            }
        }

        val repository = "MavenRepo"

        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                "debug" to listOf(
                    ResolvedDependency.fromId("androidx.core:core:1.0.0", repository)
                        .copy(requiresJetifier = true),
                    ResolvedDependency.fromId("androidx.appcompat:appcompat:1.0.0", repository)
                        .copy(requiresJetifier = true),
                    ResolvedDependency.fromId("com.google.android:material:1.0.0", repository)
                        .copy(requiresJetifier = true),
                    ResolvedDependency.fromId("junit:junit:4.12", repository)
                )
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        // Verify results
        val debugRepo = result.find { it.name == "debug_maven" }
        assertNotNull(debugRepo, "Debug repository should exist")

        // Verify excluded artifacts are not included
        val jetifiedArtifacts = debugRepo.jetifierConfig.artifacts
        assertFalse(
            "androidx.core:core should be excluded from jetifier list",
            "androidx.core:core" in jetifiedArtifacts
        )
        assertFalse(
            "com.google.android:material should be excluded from jetifier list",
            "com.google.android:material" in jetifiedArtifacts
        )

        // Verify non-excluded artifact is included
        assertTrue(
            "androidx.appcompat:appcompat should be included in jetifier list",
            "androidx.appcompat:appcompat" in jetifiedArtifacts
        )
    }

    @Test
    fun `maven install roots selected artifacts with configured conflict policy`() {
        setup {
            rules {
                mavenInstall {
                    versionConflictPolicy = "pinned"
                }
            }
        }

        val selectedDependency = ResolvedDependency.fromId(
            "com.example:library:1.0.0",
            "MavenRepo"
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(DEFAULT_VARIANT to listOf(selectedDependency))
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val repo = result.single { it.name == "maven" }
        val artifact = repo
            .artifacts
            .single()

        assertEquals("pinned", repo.versionConflictPolicy)
        assertEquals(selectedDependency.id, artifact.id)
        assertFalse("normal selected roots should stay compact simple artifacts", artifact is DetailedArtifact)
    }

    @Test
    fun `maven install roots selected transitive artifacts without synthetic ownership exclusions`() {
        setup()

        val repository = "MavenRepo"
        val rootDependency = ResolvedDependency.fromId("com.example:root:1.0.0", repository)
        val promotedDirectDependency = ResolvedDependency.fromId(
            "com.example:promoted-direct:2.0.0",
            repository
        )
        val sameRepoTransitiveDependency = ResolvedDependency.fromId(
            "com.example:same-repo-transitive:1.1.0",
            repository
        ).copy(direct = false)
        val childBucketDependency = ResolvedDependency.fromId(
            "com.example:child-bucket:1.0.0",
            repository
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(
                    rootDependency,
                    promotedDirectDependency,
                    sameRepoTransitiveDependency
                ),
                "debug" to listOf(childBucketDependency)
            ),
            transitiveClasspath = mapOf(
                rootDependency.shortId to setOf(
                    promotedDirectDependency.shortId,
                    sameRepoTransitiveDependency.shortId,
                    childBucketDependency.shortId
                )
            ),
            variantTransitiveClasspath = mapOf(
                DEFAULT_VARIANT to mapOf(
                    rootDependency.shortId to setOf(
                        promotedDirectDependency.shortId,
                        sameRepoTransitiveDependency.shortId,
                        childBucketDependency.shortId
                    )
                )
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val rootArtifact = result.single { it.name == "maven" }
            .artifacts
            .single { it.id == rootDependency.id }
        val artifactIds = result.single { it.name == "maven" }
            .artifacts
            .map { it.id }
            .toSet()

        assertEquals(
            setOf(
                rootDependency.id,
                promotedDirectDependency.id,
                sameRepoTransitiveDependency.id,
                childBucketDependency.id
            ),
            artifactIds
        )
        assertFalse(
            "bucket ownership should be represented by Maven roots and override_targets, not " +
                "synthetic per-root exclusions",
            listOf(
                childBucketDependency.shortId,
                promotedDirectDependency.shortId,
                sameRepoTransitiveDependency.shortId
            ).any {
                rootArtifact is com.grab.grazel.bazel.rules.MavenInstallArtifact.DetailedArtifact &&
                    rootArtifact.exclusions
                        .filterIsInstance<SimpleExclusion>()
                        .any { exclusion -> exclusion.coordinates == it }
            }
        )
        assertFalse("normal selected roots should stay compact simple artifacts", rootArtifact is DetailedArtifact)
    }

    @Test
    fun `default maven install roots globally selected transitive closure`() {
        setup()

        val repository = "MavenRepo"
        val rootDependency = ResolvedDependency.fromId("com.example:root:1.0.0", repository)
        val siblingBucketTransitiveDependency = ResolvedDependency
            .fromId("com.example:selected-transitive:2.0.0", repository)
            .copy(
                direct = false,
                overrideTarget = OverrideTarget(
                    artifactShortId = "com.example:selected-transitive",
                    label = MavenDependency(
                        repo = "debug_maven",
                        group = "com.example",
                        name = "selected-transitive"
                    )
                )
            )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(rootDependency),
                "debug" to listOf(siblingBucketTransitiveDependency)
            ),
            transitiveClasspath = mapOf(
                rootDependency.shortId to setOf(siblingBucketTransitiveDependency.shortId)
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val repo = result.single { it.name == "maven" }

        assertEquals(
            setOf(rootDependency.id, siblingBucketTransitiveDependency.id),
            repo.artifacts.map { artifact -> artifact.id }.toSet()
        )
        assertFalse(
            "default maven roots should not inherit child override targets",
            siblingBucketTransitiveDependency.shortId in repo.overrideTargets
        )
    }

    @Test
    fun `override target artifacts are mapped and still rooted for coursier`() {
        setup()

        val repository = "MavenRepo"
        val overriddenDependency = ResolvedDependency.fromId(
            "com.example:library:1.0.0",
            repository
        ).copy(
            overrideTarget = OverrideTarget(
                artifactShortId = "com.example:library",
                label = MavenDependency(
                    group = "com.example",
                    name = "library"
                )
            )
        )
        val childDependency = ResolvedDependency.fromId("com.example:test-only:1.0.0", repository)
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                "androidTest" to listOf(overriddenDependency, childDependency)
            ),
            transitiveClasspath = mapOf(
                childDependency.shortId to setOf(overriddenDependency.shortId)
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val androidTestRepo = result.single { it.name == "android_test_maven" }
        assertEquals(
            setOf("com.example:library:1.0.0", "com.example:test-only:1.0.0"),
            androidTestRepo.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            mapOf("com.example:library" to "@maven//:com_example_library"),
            androidTestRepo.overrideTargets
        )
    }

    @Test
    fun `override target artifacts are mapped from selected bucket closure and rooted`() {
        setup()

        val repository = "MavenRepo"
        val reachableOverride = ResolvedDependency.fromId(
            "com.example:reachable:1.0.0",
            repository
        ).copy(
            direct = false,
            overrideTarget = OverrideTarget(
                artifactShortId = "com.example:reachable",
                label = MavenDependency(
                    group = "com.example",
                    name = "reachable"
                )
            )
        )
        val unrelatedOverride = ResolvedDependency.fromId(
            "com.example:unrelated:1.0.0",
            repository
        ).copy(
            direct = false,
            overrideTarget = OverrideTarget(
                artifactShortId = "com.example:unrelated",
                label = MavenDependency(
                    group = "com.example",
                    name = "unrelated"
                )
            )
        )
        val childDependency = ResolvedDependency.fromId("com.example:test-only:1.0.0", repository)
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                "androidTest" to listOf(reachableOverride, unrelatedOverride, childDependency)
            ),
            transitiveClasspath = mapOf(
                childDependency.shortId to setOf(reachableOverride.shortId)
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val androidTestRepo = result.single { it.name == "android_test_maven" }
        assertEquals(
            setOf(
                "com.example:reachable:1.0.0",
                "com.example:test-only:1.0.0"
            ),
            androidTestRepo.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            mapOf(
                "com.example:reachable" to "@maven//:com_example_reachable"
            ),
            androidTestRepo.overrideTargets
        )
    }

    @Test
    fun `maven install drops self override targets but keeps artifact rooted`() {
        setup()

        val repository = "MavenRepo"
        val selfOverrideDependency = ResolvedDependency.fromId(
            "androidx.work:work-runtime:2.10.2",
            repository
        ).copy(
            overrideTarget = OverrideTarget(
                artifactShortId = "androidx.work:work-runtime",
                label = MavenDependency(
                    repo = "android_test_maven",
                    group = "androidx.work",
                    name = "work-runtime"
                )
            )
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                "androidTest" to listOf(selfOverrideDependency)
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val androidTestRepo = result.single { it.name == "android_test_maven" }
        assertEquals(
            setOf("androidx.work:work-runtime:2.10.2"),
            androidTestRepo.artifacts.map { it.id }.toSet()
        )
        assertEquals(emptyMap<String, String>(), androidTestRepo.overrideTargets)
    }

    @Test
    fun `maven install keeps same repo override targets when label points to different artifact`() {
        setup()

        val repository = "MavenRepo"
        val normalizedDependency = ResolvedDependency.fromId(
            "io.insert-koin:koin-core:3.2.0",
            repository
        ).copy(
            direct = false,
            overrideTarget = OverrideTarget(
                artifactShortId = "io.insert-koin:koin-core",
                label = MavenDependency(
                    repo = "maven",
                    group = "io.insert-koin",
                    name = "koin-core-jvm"
                )
            )
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(normalizedDependency)
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val mavenRepo = result.single { it.name == "maven" }
        assertEquals(
            mapOf("io.insert-koin:koin-core" to "@maven//:io_insert_koin_koin_core_jvm"),
            mavenRepo.overrideTargets
        )
    }

    @Test
    fun `extension override target wins for default owner inherited artifacts`() {
        setup {
            rules {
                mavenInstall {
                    overrideTargetLabels.putAll(
                        mapOf(
                            "androidx.annotation:annotation" to
                                "@maven//:androidx_annotation_annotation_jvm"
                        )
                    )
                }
            }
        }

        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(
                    ResolvedDependency.fromId(
                        "androidx.annotation:annotation:1.9.1",
                        DEFAULT_VARIANT
                    )
                ),
                LINT_VARIANT to listOf(
                    ResolvedDependency.fromId(
                        "com.android.tools.lint:lint-api:31.5.0-alpha02",
                        LINT_VARIANT
                    )
                )
            ),
            variantTransitiveClasspath = mapOf(
                LINT_VARIANT to mapOf(
                    "com.android.tools.lint:lint-api" to setOf(
                        "androidx.annotation:annotation"
                    )
                )
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val lintRepo = result.single { it.name == "lint_maven" }
        assertEquals(
            mapOf("androidx.annotation:annotation" to "@maven//:androidx_annotation_annotation_jvm"),
            lintRepo.overrideTargets.filterKeys { it == "androidx.annotation:annotation" }
        )
    }

    @Test
    fun `override target artifacts are mapped independently and rooted in each selected bucket closure`() {
        setup()

        val repository = "MavenRepo"
        val debugOverride = ResolvedDependency.fromId(
            "com.example:debug-carrier:1.0.0",
            repository
        ).copy(
            direct = false,
            overrideTarget = OverrideTarget(
                artifactShortId = "com.example:debug-carrier",
                label = MavenDependency(
                    group = "com.example",
                    name = "debug-carrier"
                )
            )
        )
        val androidTestOverride = ResolvedDependency.fromId(
            "com.example:android-test-carrier:1.0.0",
            repository
        ).copy(
            direct = false,
            overrideTarget = OverrideTarget(
                artifactShortId = "com.example:android-test-carrier",
                label = MavenDependency(
                    group = "com.example",
                    name = "android-test-carrier"
                )
            )
        )
        val sharedDirectRoot = ResolvedDependency.fromId("com.example:shared-root:1.0.0", repository)
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                "debug" to listOf(sharedDirectRoot, debugOverride, androidTestOverride),
                "androidTest" to listOf(sharedDirectRoot, debugOverride, androidTestOverride)
            ),
            transitiveClasspath = mapOf(
                sharedDirectRoot.shortId to setOf(
                    debugOverride.shortId,
                    androidTestOverride.shortId
                )
            ),
            variantTransitiveClasspath = mapOf(
                "debug" to mapOf(sharedDirectRoot.shortId to setOf(debugOverride.shortId)),
                "androidTest" to mapOf(sharedDirectRoot.shortId to setOf(androidTestOverride.shortId))
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val debugRepo = result.single { it.name == "debug_maven" }
        val androidTestRepo = result.single { it.name == "android_test_maven" }
        assertEquals(
            setOf(
                "com.example:debug-carrier:1.0.0",
                "com.example:shared-root:1.0.0"
            ),
            debugRepo.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            setOf(
                "com.example:android-test-carrier:1.0.0",
                "com.example:shared-root:1.0.0"
            ),
            androidTestRepo.artifacts.map { it.id }.toSet()
        )
    }

    @Test
    fun `variant maven install roots parent owned override carriers for coursier`() {
        setup()

        val repository = "MavenRepo"
        val directRoot = ResolvedDependency.fromId(
            "androidx.test.ext:junit:1.1.5",
            repository
        )
        val coreCarrier = ResolvedDependency.fromId(
            "androidx.test:core:1.5.0",
            repository
        ).copy(
            direct = false,
            overrideTarget = OverrideTarget(
                artifactShortId = "androidx.test:core",
                label = MavenDependency(
                    group = "androidx.test",
                    name = "core"
                )
            )
        )
        val monitorCarrier = ResolvedDependency.fromId(
            "androidx.test:monitor:1.6.0",
            repository
        ).copy(
            direct = false,
            overrideTarget = OverrideTarget(
                artifactShortId = "androidx.test:monitor",
                label = MavenDependency(
                    group = "androidx.test",
                    name = "monitor"
                )
            )
        )
        val storageCarrier = ResolvedDependency.fromId(
            "androidx.test.services:storage:1.4.2",
            repository
        ).copy(
            direct = false,
            overrideTarget = OverrideTarget(
                artifactShortId = "androidx.test.services:storage",
                label = MavenDependency(
                    group = "androidx.test.services",
                    name = "storage"
                )
            )
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                "gpsPaxRelease" to listOf(
                    directRoot,
                    coreCarrier,
                    monitorCarrier,
                    storageCarrier
                )
            ),
            transitiveClasspath = mapOf(
                directRoot.shortId to setOf(
                    coreCarrier.shortId,
                    monitorCarrier.shortId,
                    storageCarrier.shortId
                )
            ),
            variantTransitiveClasspath = mapOf(
                "gpsPaxRelease" to mapOf(
                    "com.example:unrelated-root" to setOf("com.example:unrelated-carrier")
                )
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val gpsPaxReleaseRepo = result.single { it.name == "gps_pax_release_maven" }
        assertEquals(
            setOf(
                "androidx.test.ext:junit:1.1.5",
                "androidx.test.services:storage:1.4.2",
                "androidx.test:core:1.5.0",
                "androidx.test:monitor:1.6.0"
            ),
            gpsPaxReleaseRepo.artifacts.map { it.id }.toSet()
        )
    }

    @Test
    fun `variant maven install redirects same version default owned selected closure`() {
        setup()

        val repository = "MavenRepo"
        val defaultLifecycleCommon = ResolvedDependency.fromId(
            "androidx.lifecycle:lifecycle-common:2.8.3",
            repository
        )
        val defaultLifecycleJava8 = ResolvedDependency.fromId(
            "androidx.lifecycle:lifecycle-common-java8:2.8.3",
            repository
        )
        val unrelatedDefaultDependency = ResolvedDependency.fromId(
            "androidx.lifecycle:lifecycle-process:2.8.3",
            repository
        )
        val testRoot = ResolvedDependency.fromId(
            "androidx.compose.ui:ui-test-junit4:1.8.3",
            repository
        )
        val lifecycleJvmCarrier = ResolvedDependency.fromId(
            "androidx.lifecycle:lifecycle-common-jvm:2.8.3",
            repository
        ).copy(
            direct = false,
            overrideTarget = OverrideTarget(
                artifactShortId = "androidx.lifecycle:lifecycle-common-jvm",
                label = MavenDependency(
                    group = "androidx.lifecycle",
                    name = "lifecycle-common-jvm"
                )
            )
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(
                    defaultLifecycleCommon,
                    defaultLifecycleJava8,
                    unrelatedDefaultDependency
                ),
                "debugAndroidTest" to listOf(testRoot, lifecycleJvmCarrier)
            ),
            variantTransitiveClasspath = mapOf(
                "debugAndroidTest" to mapOf(
                    testRoot.shortId to setOf(
                        defaultLifecycleCommon.shortId,
                        defaultLifecycleJava8.shortId,
                        lifecycleJvmCarrier.shortId
                    )
                )
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val debugAndroidTestRepo = result.single { it.name == "debug_android_test_maven" }
        assertEquals(
            setOf(
                "androidx.compose.ui:ui-test-junit4:1.8.3",
                "androidx.lifecycle:lifecycle-common-java8:2.8.3",
                "androidx.lifecycle:lifecycle-common:2.8.3",
                "androidx.lifecycle:lifecycle-common-jvm:2.8.3"
            ),
            debugAndroidTestRepo.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            mapOf(
                "androidx.lifecycle:lifecycle-common" to "@maven//:androidx_lifecycle_lifecycle_common",
                "androidx.lifecycle:lifecycle-common-java8" to
                    "@maven//:androidx_lifecycle_lifecycle_common_java8",
                "androidx.lifecycle:lifecycle-common-jvm" to "@maven//:androidx_lifecycle_lifecycle_common_jvm"
            ),
            debugAndroidTestRepo.overrideTargets
        )
    }

    @Test
    fun `variant maven install redirects default closure for existing bucket artifacts`() {
        setup()

        val repository = "MavenRepo"
        val defaultLifecycleCommon = ResolvedDependency.fromId(
            "androidx.lifecycle:lifecycle-common:2.8.3",
            repository
        )
        val defaultLifecycleJava8 = ResolvedDependency.fromId(
            "androidx.lifecycle:lifecycle-common-java8:2.8.3",
            repository
        )
        val unrelatedDefaultDependency = ResolvedDependency.fromId(
            "androidx.fragment:fragment:1.6.2",
            repository
        )
        val testRoot = ResolvedDependency.fromId(
            "androidx.compose.ui:ui-test-junit4:1.8.3",
            repository
        )
        val lifecycleRuntimeCarrier = ResolvedDependency.fromId(
            "androidx.lifecycle:lifecycle-runtime:2.8.3",
            repository
        ).copy(
            direct = false,
            overrideTarget = OverrideTarget(
                artifactShortId = "androidx.lifecycle:lifecycle-runtime",
                label = MavenDependency(
                    group = "androidx.lifecycle",
                    name = "lifecycle-runtime"
                )
            )
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(
                    defaultLifecycleCommon,
                    defaultLifecycleJava8,
                    unrelatedDefaultDependency
                ),
                "debugAndroidTest" to listOf(testRoot, lifecycleRuntimeCarrier)
            ),
            transitiveClasspath = mapOf(
                lifecycleRuntimeCarrier.shortId to setOf(
                    defaultLifecycleCommon.shortId,
                    defaultLifecycleJava8.shortId,
                    lifecycleRuntimeCarrier.shortId
                )
            ),
            variantTransitiveClasspath = mapOf(
                "debugAndroidTest" to mapOf(
                    testRoot.shortId to setOf(
                        defaultLifecycleCommon.shortId,
                        defaultLifecycleJava8.shortId,
                        lifecycleRuntimeCarrier.shortId
                    )
                )
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val debugAndroidTestRepo = result.single { it.name == "debug_android_test_maven" }
        assertEquals(
            setOf(
                "androidx.compose.ui:ui-test-junit4:1.8.3",
                "androidx.lifecycle:lifecycle-common-java8:2.8.3",
                "androidx.lifecycle:lifecycle-common:2.8.3",
                "androidx.lifecycle:lifecycle-runtime:2.8.3"
            ),
            debugAndroidTestRepo.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            mapOf(
                "androidx.lifecycle:lifecycle-common" to "@maven//:androidx_lifecycle_lifecycle_common",
                "androidx.lifecycle:lifecycle-common-java8" to
                    "@maven//:androidx_lifecycle_lifecycle_common_java8",
                "androidx.lifecycle:lifecycle-runtime" to "@maven//:androidx_lifecycle_lifecycle_runtime"
            ),
            debugAndroidTestRepo.overrideTargets
        )
    }

    @Test
    fun `variant maven install does not inherit default roots from global transitive classpath`() {
        setup()

        val repository = "MavenRepo"
        val defaultRoot = ResolvedDependency.fromId(
            "com.google.firebase:firebase-analytics:22.1.0",
            repository
        ).copy(
            dependencies = setOf(
                "com.google.android.gms:play-services-measurement-api:22.0.2:maven:false:null"
            )
        )
        val defaultTransitive = ResolvedDependency.fromId(
            "com.google.android.gms:play-services-measurement-api:22.0.2",
            repository
        ).copy(direct = false)
        val flavorRoot = ResolvedDependency.fromId(
            "com.example:gps-only:1.0.0",
            repository
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(defaultRoot, defaultTransitive),
                "gps" to listOf(flavorRoot)
            ),
            transitiveClasspath = mapOf(
                defaultRoot.shortId to setOf(defaultTransitive.shortId)
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val gpsRepo = result.single { it.name == "gps_maven" }
        assertEquals(
            setOf("com.example:gps-only:1.0.0"),
            gpsRepo.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            emptyMap<String, String>(),
            gpsRepo.overrideTargets
        )
    }

    @Test
    fun `variant maven install redirects same resolved default owned transitive`() {
        setup()

        val repository = "MavenRepo"
        val sharedNotation = "com.example:shared:1.0.0:$repository:false:null"
        val mainRoot = ResolvedDependency.fromId(
            "com.example:main:1.0.0",
            repository
        ).copy(
            dependencies = setOf(sharedNotation)
        )
        val androidTestRoot = ResolvedDependency.fromId(
            "com.example:test-helper:1.0.0",
            repository
        ).copy(
            dependencies = setOf(sharedNotation)
        )
        val workspaceDependencies = ComputeWorkspaceDependencies().computeFromResults(
            listOf(
                result(DEFAULT_VARIANT, mainRoot),
                result("androidTest", androidTestRoot)
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val androidTestRepo = result.single { it.name == "android_test_maven" }
        assertEquals(
            setOf(
                "com.example:shared:1.0.0",
                "com.example:test-helper:1.0.0"
            ),
            androidTestRepo.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            mapOf("com.example:shared" to "@maven//:com_example_shared"),
            androidTestRepo.overrideTargets.filterKeys { it == "com.example:shared" }
        )
        assertFalse(
            "generated force-version options should not replace artifact closure constraints",
            "--force-version" in androidTestRepo.additionalCoursierOptions
        )
    }

    @Test
    fun `variant maven install does not root flattened child artifacts unreachable from child roots`() {
        setup()

        val repository = "MavenRepo"
        val testRoot = ResolvedDependency.fromId(
            "com.example:test-root:1.0.0",
            repository
        )
        val reachableShared = ResolvedDependency.fromId(
            "com.example:reachable-shared:1.0.0",
            repository
        ).copy(direct = false)
        val unreachableFlattenedCarrier = ResolvedDependency.fromId(
            "com.example:unreachable-flattened:1.0.0",
            repository
        ).copy(direct = false)
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(reachableShared, unreachableFlattenedCarrier),
                "androidTest" to listOf(testRoot, reachableShared, unreachableFlattenedCarrier)
            ),
            variantTransitiveClasspath = mapOf(
                "androidTest" to mapOf(testRoot.shortId to setOf(reachableShared.shortId))
            ),
            transitiveClasspath = mapOf(
                testRoot.shortId to setOf(reachableShared.shortId)
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val androidTestRepo = result.single { it.name == "android_test_maven" }
        assertEquals(
            setOf(
                "com.example:reachable-shared:1.0.0",
                "com.example:test-root:1.0.0"
            ),
            androidTestRepo.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            mapOf("com.example:reachable-shared" to "@maven//:com_example_reachable_shared"),
            androidTestRepo.overrideTargets
        )
    }

    @Test
    fun `variant maven install roots resolved dependencies carried by rooted artifacts`() {
        setup()

        val repository = "MavenRepo"
        val monitorNotation = "androidx.test:monitor:1.6.0:$repository:false:null"
        val rootedArtifact = ResolvedDependency.fromId(
            "androidx.test.ext:junit-ktx:1.1.5",
            DECLARED_DEPENDENCY_REPOSITORY
        ).copy(
            dependencies = setOf(monitorNotation)
        )
        val monitorDependency = ResolvedDependency.fromId(
            "androidx.test:monitor:1.6.0",
            repository
        ).copy(direct = false)
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(monitorDependency),
                "debug" to listOf(rootedArtifact)
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val debugRepo = result.single { it.name == "debug_maven" }
        assertEquals(
            setOf(
                "androidx.test.ext:junit-ktx:1.1.5",
                "androidx.test:monitor:1.6.0"
            ),
            debugRepo.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            mapOf("androidx.test:monitor" to "@maven//:androidx_test_monitor"),
            debugRepo.overrideTargets.filterKeys { it == "androidx.test:monitor" }
        )
        assertFalse(
            "generated force-version options should not replace artifact closure constraints",
            "--force-version" in debugRepo.additionalCoursierOptions
        )
    }

    @Test
    fun `variant maven install roots selected closure for promoted rooted artifacts`() {
        setup()

        val repository = "MavenRepo"
        val carrier = ResolvedDependency.fromId(
            "com.credolab:modular.audio:4.0.0",
            repository
        )
        val junitKtx = ResolvedDependency.fromId(
            "androidx.test.ext:junit-ktx:1.1.5",
            DECLARED_DEPENDENCY_REPOSITORY
        )
        val monitor = ResolvedDependency.fromId(
            "androidx.test:monitor:1.6.0",
            repository
        ).copy(direct = false)
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(monitor),
                "debug" to listOf(junitKtx)
            ),
            transitiveClasspath = mapOf(
                carrier.shortId to setOf(junitKtx.shortId, monitor.shortId)
            ),
            variantTransitiveClasspath = mapOf(
                "debug" to mapOf(carrier.shortId to setOf(junitKtx.shortId))
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val debugRepo = result.single { it.name == "debug_maven" }
        assertEquals(
            setOf(
                "androidx.test.ext:junit-ktx:1.1.5",
                "androidx.test:monitor:1.6.0"
            ),
            debugRepo.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            mapOf("androidx.test:monitor" to "@maven//:androidx_test_monitor"),
            debugRepo.overrideTargets.filterKeys { it == "androidx.test:monitor" }
        )
        assertFalse(
            "generated force-version options should not replace artifact closure constraints",
            "--force-version" in debugRepo.additionalCoursierOptions
        )
    }

    @Test
    fun `variant maven install uses scoped closure instead of global sibling carriers`() {
        setup()

        val repository = "MavenRepo"
        val sharedDirectRoot = ResolvedDependency.fromId("com.example:shared-root:1.0.0", repository)
        val debugCarrier = ResolvedDependency.fromId(
            "com.example:debug-carrier:1.0.0",
            repository
        ).copy(
            direct = false,
            overrideTarget = OverrideTarget(
                artifactShortId = "com.example:debug-carrier",
                label = MavenDependency(
                    group = "com.example",
                    name = "debug-carrier"
                )
            )
        )
        val androidTestCarrier = ResolvedDependency.fromId(
            "com.example:android-test-carrier:1.0.0",
            repository
        ).copy(
            direct = false,
            overrideTarget = OverrideTarget(
                artifactShortId = "com.example:android-test-carrier",
                label = MavenDependency(
                    group = "com.example",
                    name = "android-test-carrier"
                )
            )
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(debugCarrier, androidTestCarrier),
                "debug" to listOf(sharedDirectRoot, debugCarrier),
                "androidTest" to listOf(sharedDirectRoot, androidTestCarrier)
            ),
            transitiveClasspath = mapOf(
                sharedDirectRoot.shortId to setOf(
                    debugCarrier.shortId,
                    androidTestCarrier.shortId
                )
            ),
            variantTransitiveClasspath = mapOf(
                "debug" to mapOf(sharedDirectRoot.shortId to setOf(debugCarrier.shortId)),
                "androidTest" to mapOf(sharedDirectRoot.shortId to setOf(androidTestCarrier.shortId))
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        assertEquals(
            setOf(
                "com.example:debug-carrier:1.0.0",
                "com.example:shared-root:1.0.0"
            ),
            result.single { it.name == "debug_maven" }.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            setOf(
                "com.example:android-test-carrier:1.0.0",
                "com.example:shared-root:1.0.0"
            ),
            result.single { it.name == "android_test_maven" }.artifacts.map { it.id }.toSet()
        )
    }

    @Test
    fun `variant maven install roots global closure for rooted artifacts without sibling carriers`() {
        setup()

        val repository = "MavenRepo"
        val scopedRoot = ResolvedDependency.fromId("com.example:scoped-root:1.0.0", repository)
        val scopedCarrier = ResolvedDependency.fromId(
            "com.example:scoped-carrier:1.0.0",
            repository
        ).copy(direct = false)
        val declaredRoot = ResolvedDependency.fromId(
            "androidx.test.ext:junit:1.1.5",
            DECLARED_DEPENDENCY_REPOSITORY
        )
        val globallyResolvedCarrier = ResolvedDependency.fromId(
            "androidx.test:monitor:1.6.0",
            repository
        ).copy(direct = false)
        val unrelatedSiblingCarrier = ResolvedDependency.fromId(
            "com.example:android-test-carrier:1.0.0",
            repository
        ).copy(direct = false)
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(globallyResolvedCarrier),
                "debug" to listOf(scopedRoot, scopedCarrier, declaredRoot),
                "androidTest" to listOf(declaredRoot, globallyResolvedCarrier, unrelatedSiblingCarrier)
            ),
            transitiveClasspath = mapOf(
                scopedRoot.shortId to setOf(scopedCarrier.shortId, unrelatedSiblingCarrier.shortId),
                declaredRoot.shortId to setOf(globallyResolvedCarrier.shortId)
            ),
            variantTransitiveClasspath = mapOf(
                "debug" to mapOf(scopedRoot.shortId to setOf(scopedCarrier.shortId)),
                "androidTest" to mapOf(declaredRoot.shortId to setOf(globallyResolvedCarrier.shortId))
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        assertEquals(
            setOf(
                "androidx.test.ext:junit:1.1.5",
                "androidx.test:monitor:1.6.0",
                "com.example:scoped-carrier:1.0.0",
                "com.example:scoped-root:1.0.0"
            ),
            result.single { it.name == "debug_maven" }.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            mapOf("androidx.test:monitor" to "@maven//:androidx_test_monitor"),
            result.single { it.name == "debug_maven" }
                .overrideTargets
                .filterKeys { it == "androidx.test:monitor" }
        )
    }

    @Test
    fun `default maven install uses scoped closure instead of global sibling carriers`() {
        setup()

        val repository = "MavenRepo"
        val defaultRoot = ResolvedDependency.fromId(
            "com.example:shared-root:1.0.0",
            repository
        )
        val defaultCarrier = ResolvedDependency.fromId(
            "com.example:default-carrier:1.0.0",
            repository
        ).copy(direct = false)
        val debugCarrier = ResolvedDependency.fromId(
            "com.example:debug-carrier:1.0.0",
            repository
        ).copy(direct = false)
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(defaultRoot, defaultCarrier),
                "debug" to listOf(debugCarrier)
            ),
            transitiveClasspath = mapOf(
                defaultRoot.shortId to setOf(defaultCarrier.shortId, debugCarrier.shortId)
            ),
            variantTransitiveClasspath = mapOf(
                DEFAULT_VARIANT to mapOf(defaultRoot.shortId to setOf(defaultCarrier.shortId)),
                "debug" to mapOf(defaultRoot.shortId to setOf(debugCarrier.shortId))
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        assertEquals(
            setOf(
                "com.example:default-carrier:1.0.0",
                "com.example:shared-root:1.0.0"
            ),
            result.single { it.name == "maven" }.artifacts.map { it.id }.toSet()
        )
    }

    @Test
    fun `variant maven install roots non default owned reachable artifacts`() {
        setup()

        val repository = "MavenRepo"
        val debugCarrier = ResolvedDependency.fromId(
            "com.example:debug-carrier:1.0.0",
            repository
        )
        val freeRoot = ResolvedDependency.fromId(
            "com.example:free-root:1.0.0",
            repository
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to emptyList(),
                "debug" to listOf(debugCarrier),
                "free" to listOf(freeRoot)
            ),
            variantTransitiveClasspath = mapOf(
                "free" to mapOf(freeRoot.shortId to setOf(debugCarrier.shortId))
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val freeRepo = result.single { it.name == "free_maven" }
        assertEquals(
            setOf("com.example:debug-carrier:1.0.0", "com.example:free-root:1.0.0"),
            freeRepo.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            mapOf("com.example:debug-carrier" to "@debug_maven//:com_example_debug_carrier"),
            freeRepo.overrideTargets
        )
    }

    @Test
    fun `variant maven install redirects default closure artifacts`() {
        setup()

        val repository = "MavenRepo"
        val defaultOwnedTransitive = ResolvedDependency.fromId(
            "com.example:shared-transitive:2.0.0",
            repository
        ).copy(direct = false)
        val debugRoot = ResolvedDependency.fromId(
            "com.example:debug-root:1.0.0",
            repository
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(defaultOwnedTransitive),
                "debug" to listOf(debugRoot)
            ),
            variantTransitiveClasspath = mapOf(
                "debug" to mapOf(debugRoot.shortId to setOf(defaultOwnedTransitive.shortId))
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val debugRepo = result.single { it.name == "debug_maven" }
        assertEquals(
            setOf(
                "com.example:debug-root:1.0.0",
                "com.example:shared-transitive:2.0.0"
            ),
            debugRepo.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            mapOf("com.example:shared-transitive" to "@maven//:com_example_shared_transitive"),
            debugRepo.overrideTargets
        )
    }

    @Test
    fun `variant maven install redirects default owned databinding closure artifacts`() {
        setup()

        val repository = "MavenRepo"
        val databindingAdapter = ResolvedDependency.fromId(
            "androidx.databinding:databinding-adapters:8.6.1",
            repository
        ).copy(direct = false)
        val debugRoot = ResolvedDependency.fromId(
            "com.example:debug-root:1.0.0",
            repository
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(databindingAdapter),
                "debug" to listOf(debugRoot)
            ),
            variantTransitiveClasspath = mapOf(
                "debug" to mapOf(debugRoot.shortId to setOf(databindingAdapter.shortId))
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val debugRepo = result.single { it.name == "debug_maven" }
        assertEquals(
            setOf(
                "androidx.databinding:databinding-adapters:8.6.1",
                "com.example:debug-root:1.0.0"
            ),
            debugRepo.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            mapOf(
                "androidx.databinding:databinding-adapters" to
                    "@maven//:androidx_databinding_databinding_adapters"
            ),
            debugRepo.overrideTargets
        )
    }

    @Test
    fun `non default maven install does not redirect unrelated default owned artifacts`() {
        setup()

        val repository = "MavenRepo"
        val defaultOwnedTransitive = ResolvedDependency.fromId(
            "com.example:shared-transitive:1.0.0",
            repository
        )
        val debugRoot = ResolvedDependency.fromId(
            "com.example:debug-root:1.0.0",
            repository
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(defaultOwnedTransitive),
                "debug" to listOf(debugRoot)
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        assertEquals(
            emptyMap<String, String>(),
            result.single { it.name == "debug_maven" }.overrideTargets
        )
    }

    @Test
    fun `maven installs are filtered by render plan materialized repos`() {
        setup()

        val repository = "MavenRepo"
        val defaultDependency = ResolvedDependency.fromId(
            "com.example:default:1.0.0",
            repository
        )
        val debugDependency = ResolvedDependency.fromId(
            "com.example:debug:1.0.0",
            repository
        )
        val unreferencedFlavorDependency = ResolvedDependency.fromId(
            "com.example:flavor:1.0.0",
            repository
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(defaultDependency),
                "debug" to listOf(debugDependency),
                "moveit" to listOf(unreferencedFlavorDependency)
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet(),
            materializedMavenRepos = setOf("debug_maven", "maven")
        )

        assertEquals(
            setOf("debug_maven", "maven"),
            result.map(MavenInstallData::name).toSet()
        )
    }

    @Test
    fun `render plan materialized maven repos are used as exact repo selection`() {
        setup()

        val repository = "MavenRepo"
        val defaultDependency = ResolvedDependency.fromId(
            "com.example:default:1.0.0",
            repository
        )
        val debugDependency = ResolvedDependency.fromId(
            "com.example:debug:1.0.0",
            repository
        )
        val processorDependency = ResolvedDependency.fromId(
            "com.example:processor:1.0.0",
            repository
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(defaultDependency),
                "debug" to listOf(debugDependency)
            ),
            aggregatedRepos = mapOf("ksp_maven" to listOf(processorDependency))
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet(),
            materializedMavenRepos = setOf("debug_maven")
        )

        assertEquals(
            setOf("debug_maven"),
            result.map(MavenInstallData::name).toSet()
        )
    }

    @Test
    fun `materialized maven install keeps repos required by override targets`() {
        setup()

        val repository = "MavenRepo"
        val debugCarrier = ResolvedDependency.fromId(
            "com.example:debug-carrier:1.0.0",
            repository
        )
        val freeRoot = ResolvedDependency.fromId(
            "com.example:free-root:1.0.0",
            repository
        )
        val unreferencedFlavorDependency = ResolvedDependency.fromId(
            "com.example:paid-root:1.0.0",
            repository
        )
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to emptyList(),
                "debug" to listOf(debugCarrier),
                "free" to listOf(freeRoot),
                "paid" to listOf(unreferencedFlavorDependency)
            ),
            variantTransitiveClasspath = mapOf(
                "free" to mapOf(freeRoot.shortId to setOf(debugCarrier.shortId))
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet(),
            materializedMavenRepos = setOf("debug_maven", "free_maven")
        )

        assertEquals(
            setOf("debug_maven", "free_maven"),
            result.map(MavenInstallData::name).toSet()
        )
        assertEquals(
            mapOf("com.example:debug-carrier" to "@debug_maven//:com_example_debug_carrier"),
            result.single { it.name == "free_maven" }.overrideTargets
        )
    }

    @Test
    fun `non default maven install resolves direct artifacts with configured conflict policy`() {
        setup {
            rules {
                mavenInstall {
                    versionConflictPolicy = "pinned"
                }
            }
        }

        val directDependency = ResolvedDependency.fromId(
            "androidx.example:direct:1.0.0",
            "Google"
        )
        val transitiveDependency = ResolvedDependency.fromId(
            "org.jetbrains.kotlin:kotlin-stdlib:1.9.25",
            "MavenRepo"
        ).copy(direct = false)
        val defaultOnlyDependency = ResolvedDependency.fromId(
            "com.example:default-only:1.0.0",
            "MavenRepo"
        ).copy(direct = false)
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to listOf(transitiveDependency, defaultOnlyDependency),
                "debug" to listOf(directDependency, transitiveDependency)
            ),
            variantTransitiveClasspath = mapOf(
                "debug" to mapOf(directDependency.shortId to setOf(transitiveDependency.shortId))
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val defaultRepo = result.single { it.name == "maven" }
        val debugRepo = result.single { it.name == "debug_maven" }
        assertEquals("pinned", defaultRepo.versionConflictPolicy)
        assertEquals("pinned", debugRepo.versionConflictPolicy)
        assertEquals(
            setOf("androidx.example:direct:1.0.0", "org.jetbrains.kotlin:kotlin-stdlib:1.9.25"),
            debugRepo.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            setOf(
                "https://dl.google.com/dl/android/maven2/",
                "https://repo.maven.apache.org/maven2/"
            ),
            debugRepo.repositories.filterIsInstance<DefaultMavenRepository>().map { it.url }.toSet()
        )
        val debugArtifact = debugRepo.artifacts.single { it.id == "androidx.example:direct:1.0.0" }
        assertFalse("normal direct roots should stay compact simple artifacts", debugArtifact is DetailedArtifact)
    }

    @Test
    fun `lint maven install roots selected transitive artifacts`() {
        setup()

        val repository = "MavenRepo"
        val lintChecksDependency = ResolvedDependency.fromId(
            "com.example:lint-checks:1.0.0",
            repository
        )
        val selectedTransitiveDependency = ResolvedDependency.fromId(
            "com.example:annotations:1.1.0",
            repository
        ).copy(direct = false)
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to emptyList(),
                LINT_VARIANT to listOf(lintChecksDependency, selectedTransitiveDependency)
            ),
            variantTransitiveClasspath = mapOf(
                LINT_VARIANT to mapOf(
                    lintChecksDependency.shortId to setOf(selectedTransitiveDependency.shortId)
                )
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val lintRepo = result.single { it.name == "lint_maven" }
        assertEquals(
            setOf("com.example:annotations:1.1.0", "com.example:lint-checks:1.0.0"),
            lintRepo.artifacts.map { it.id }.toSet()
        )
    }

    @Test
    fun `lint maven install keeps non direct lint tool roots`() {
        setup()

        val repository = "MavenRepo"
        val lintChecksDependency = ResolvedDependency.fromId(
            "com.example:lint-checks:1.0.0",
            repository
        )
        val lintApiDependency = ResolvedDependency.fromId(
            "com.android.tools.lint:lint-api:31.5.0",
            repository
        ).copy(direct = false)
        val lintModelDependency = ResolvedDependency.fromId(
            "com.android.tools.lint:lint-model:31.5.0",
            repository
        ).copy(direct = false)
        val workspaceDependencies = WorkspaceDependencies(
            variantDeps = mapOf(
                DEFAULT_VARIANT to emptyList(),
                LINT_VARIANT to listOf(lintChecksDependency, lintApiDependency, lintModelDependency)
            ),
            variantTransitiveClasspath = mapOf(
                LINT_VARIANT to mapOf(
                    lintApiDependency.shortId to setOf(lintModelDependency.shortId)
                )
            )
        )

        val result = calculateMavenInstallArtifacts(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val lintRepo = result.single { it.name == "lint_maven" }
        assertEquals(
            setOf(
                "com.android.tools.lint:lint-api:31.5.0",
                "com.android.tools.lint:lint-model:31.5.0",
                "com.example:lint-checks:1.0.0"
            ),
            lintRepo.artifacts.map { it.id }.toSet()
        )
    }

    private fun calculateMavenInstallArtifacts(
        layout: ProjectLayout,
        workspaceDependencies: WorkspaceDependencies,
        externalArtifacts: Set<String>,
        externalRepositories: Set<String>,
        materializedMavenRepos: Set<String> = workspaceDependencies.allMavenRepoNames()
    ): Set<MavenInstallData> = mavenInstallArtifactsCalculator.get(
        layout = layout,
        workspaceDependencies = workspaceDependencies,
        externalArtifacts = externalArtifacts,
        externalRepositories = externalRepositories,
        materializedMavenRepos = materializedMavenRepos
    )

    private fun WorkspaceDependencies.allMavenRepoNames(): Set<String> =
        (variantDeps.keys.map(String::toMavenRepoName) + aggregatedRepos.keys).toSet()

    private fun result(
        variantName: String,
        vararg dependencies: ResolvedDependency
    ): ResolveDependenciesResult {
        return ResolveDependenciesResult(
            variantName = variantName,
            dependencies = mapOf(
                COMPILE.name to dependencies.toSet(),
                KSP.name to emptySet()
            )
        )
    }
}
