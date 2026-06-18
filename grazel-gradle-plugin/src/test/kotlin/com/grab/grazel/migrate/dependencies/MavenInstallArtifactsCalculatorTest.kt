package com.grab.grazel.migrate.dependencies

import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.rules.MavenInstallArtifact.DetailedArtifact
import com.grab.grazel.bazel.rules.MavenInstallArtifact.Exclusion.SimpleExclusion
import com.grab.grazel.bazel.rules.MavenRepository.DefaultMavenRepository
import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.dependencies.model.OverrideTarget
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

        val result = mavenInstallArtifactsCalculator.get(
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
    fun `override target artifacts are resolved in child maven install`() {
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

        val result = mavenInstallArtifactsCalculator.get(
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
    fun `override target artifacts are only resolved in child maven install when reachable from direct roots`() {
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

        val result = mavenInstallArtifactsCalculator.get(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val androidTestRepo = result.single { it.name == "android_test_maven" }
        assertEquals(
            setOf("com.example:reachable:1.0.0", "com.example:test-only:1.0.0"),
            androidTestRepo.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            mapOf("com.example:reachable" to "@maven//:com_example_reachable"),
            androidTestRepo.overrideTargets
        )
    }

    @Test
    fun `override target reachability is scoped to each variant maven install`() {
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

        val result = mavenInstallArtifactsCalculator.get(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val debugRepo = result.single { it.name == "debug_maven" }
        val androidTestRepo = result.single { it.name == "android_test_maven" }
        assertEquals(
            setOf("com.example:debug-carrier:1.0.0", "com.example:shared-root:1.0.0"),
            debugRepo.artifacts.map { it.id }.toSet()
        )
        assertEquals(
            setOf("com.example:android-test-carrier:1.0.0", "com.example:shared-root:1.0.0"),
            androidTestRepo.artifacts.map { it.id }.toSet()
        )
    }

    @Test
    fun `non default maven install resolves direct artifacts only with all supported repositories`() {
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
            )
        )

        val result = mavenInstallArtifactsCalculator.get(
            layout = rootProject.layout,
            workspaceDependencies = workspaceDependencies,
            externalArtifacts = emptySet(),
            externalRepositories = emptySet()
        )

        val defaultRepo = result.single { it.name == "maven" }
        val debugRepo = result.single { it.name == "debug_maven" }
        assertEquals("pinned", defaultRepo.versionConflictPolicy)
        assertEquals(null, debugRepo.versionConflictPolicy)
        assertEquals(
            setOf("androidx.example:direct:1.0.0"),
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
            as DetailedArtifact
        val exclusionIds = debugArtifact.exclusions
            .filterIsInstance<SimpleExclusion>()
            .map { it.coordinates }
            .toSet()
        assertTrue(
            "default-only dependency should be excluded from child root",
            "com.example:default-only" in exclusionIds
        )
        assertFalse(
            "child Gradle closure dependency should not be excluded from child root",
            "org.jetbrains.kotlin:kotlin-stdlib" in exclusionIds
        )
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
            )
        )

        val result = mavenInstallArtifactsCalculator.get(
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
}
