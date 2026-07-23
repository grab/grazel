package com.grab.grazel.migrate.dependencies

import com.android.build.gradle.AppExtension
import com.grab.grazel.GrazelExtension
import com.grab.grazel.bazel.exec.bazelCommand
import com.grab.grazel.bazel.starlark.BazelDependency.MavenDependency
import com.grab.grazel.buildProject
import com.grab.grazel.di.GradleServices
import com.grab.grazel.fake.FakeLogger
import com.grab.grazel.fake.FakeWorkerExecutor
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.gradle.dependencies.model.CandidateMavenRepo
import com.grab.grazel.gradle.dependencies.model.CandidateMavenRepoKind
import com.grab.grazel.gradle.dependencies.model.OverrideTarget
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspacePlan
import com.grab.grazel.gradle.dependencies.model.WorkspaceRenderPlan
import com.grab.grazel.util.BUILD_BAZEL
import com.grab.grazel.util.NoOpProgressLogger
import com.grab.grazel.util.ROOT_PATH
import com.grab.grazel.util.WORKSPACE
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.assertNoThrow
import com.grab.grazel.util.doEvaluate
import com.grab.grazel.util.startOperation
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.the
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.io.path.copyTo
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultArtifactPinnerTest {

    private lateinit var rootProject: Project
    private lateinit var rootProjectDir: File

    private lateinit var appProject: Project
    private lateinit var artifactPinner: DefaultArtifactPinner
    private lateinit var fakeLogger: FakeLogger

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        fakeLogger = FakeLogger()
        rootProjectDir = temporaryFolder.newFolder("project")
        rootProject = buildProject("root", projectDir = rootProjectDir)
        rootProject.addGrazelExtension()

        rootProject.file(WORKSPACE).writeText("")
        rootProject.file(BUILD_BAZEL).writeText("")
        rootProject.file("maven_install.json").writeText("")
        rootProject.file(".bazelrc").writeText(
            """
            common --enable_bzlmod=false
            common --enable_workspace=true
            """.trimIndent()
        )
        ROOT_PATH.resolve(".bazelversion").copyTo(rootProject.file(".bazelversion").toPath())

        artifactPinner = DefaultArtifactPinner(rootProject.the<GrazelExtension>())

        appProject = buildProject("android-binary", rootProject)
        appProject.run {
            plugins.apply {
                apply(ANDROID_APPLICATION_PLUGIN)
            }
            extensions.configure<AppExtension> {
                namespace = "test"
                defaultConfig {
                    compileSdkVersion(23)
                }
            }
            doEvaluate()
        }
    }

    @Test
    fun `assert pinning is done when maven_install_json is commented in workspace`() {
        val workspace = rootProject.file(WORKSPACE).apply {
            writeText(
                """
                #maven_install_json = "//:maven_install.json",
                """.trimIndent()
            )
        }
        assertTrue("Pinning is done when maven_install_json is commented in workspace") {
            val gradleServices = GradleServices.from(rootProject)
            artifactPinner.shouldRunPinning(
                workspace,
                gradleServices = gradleServices,
                parentProgress = gradleServices.progressLoggerFactory.startOperation("test"),
                logger = FakeLogger(),
                pinnableRepos = emptyMap()
            )
        }
    }

    @Test
    fun `assert pinning is done when artifacts are actually out of date`() {
        val workspace = rootProject.file(WORKSPACE).apply {
            writeText(
                WORKSPACE_TEMPLATE.format(
                    "\"androidx.annotation:annotation:1.2.1\",",
                    ""
                ) // Out of date artifact
            )
        }

        rootProject.file("maven_install.json").writeText(MAVEN_INSTALL_JSON)
        val annotation = ResolvedDependency.from("androidx.annotation:annotation:1.2.0:maven:false:null")

        assertTrue("Pinning is done when artifacts are actually out of date")
        {
            val gradleServices = GradleServices.from(rootProject)
            artifactPinner.shouldRunPinning(
                workspace,
                gradleServices = gradleServices,
                parentProgress = gradleServices.progressLoggerFactory.startOperation("test"),
                logger = rootProject.logger,
                pinnableRepos = mapOf("maven" to listOf(annotation)),
                logOutput = true
            )
        }
    }

    @Test
    fun `pinning activates workspace and submits one worker action`() {
        val workspace = rootProject.file(WORKSPACE).apply {
            writeText(
                WORKSPACE_TEMPLATE.format(
                    "\"androidx.annotation:annotation:1.1.0\",",
                    "#"
                ) // Out of date artifact
            )
        }

        rootProject.file("maven_install.json").delete()

        val workerExecutor = FakeWorkerExecutor()
        val gradleServices = GradleServices.from(rootProject).copy(
            workerExecutor = workerExecutor
        )
        val annotation = ResolvedDependency.from("androidx.annotation:annotation:1.1.0:maven:false:null")
        assertTrue("Pinning is done and maven install json is generated") {
            artifactPinner.pinArtifacts(
                workspace,
                workspacePlan = workspacePlan("maven" to listOf(annotation)),
                workspaceRenderPlan = WorkspaceRenderPlan(materializedRepoNames = setOf("maven")),
                mavenInstallRepositoryInputs = MavenInstallRepositoryInputs(emptyMap()),
                gradleServices = gradleServices,
                logger = rootProject.logger,
            )
        }
        val activatedWorkspace = workspace.readText()
        assertTrue("maven_install_json is activated") {
            "maven_install_json = \"//:maven_install.json\"" in activatedWorkspace
        }
        assertTrue("pinned macro load is activated") {
            """load("@maven//:defs.bzl", maven_pinned_maven_install = "pinned_maven_install")""" in activatedWorkspace
        }
        assertTrue("pinned macro call is activated") {
            "maven_pinned_maven_install()" in activatedWorkspace
        }
        assertEquals(1, workerExecutor.workQueue.submitCount)
        assertEquals(1, workerExecutor.workQueue.awaitCount)
    }

    @Test
    fun `assert pinning target is chosen based on maven install json availability`() {
        assertEquals(
            "@unpinned_maven//:pin",
            artifactPinner.determinePinningTarget(
                rootProject.layout,
                "maven"
            )
        )
        rootProject.file("maven_install.json").delete()
        assertEquals(
            "@maven//:pin",
            artifactPinner.determinePinningTarget(
                rootProject.layout,
                "maven"
            )
        )
    }

    @Test
    fun `stale maven install jsons are deleted when repo is no longer generated`() {
        val activeDefault = rootProject.file("maven_install.json").apply { writeText("{}") }
        val activeDebug = rootProject.file("debug_maven_install.json").apply { writeText("{}") }
        val staleRelease = rootProject.file("release_maven_install.json").apply { writeText("{}") }
        val staleLeaf = rootProject.file("gps_pax_staging_maven_install.json").apply { writeText("{}") }
        val unrelated = rootProject.file("not_a_grazel_lock.json").apply { writeText("{}") }

        artifactPinner.cleanupStaleMavenInstallJsons(
            layout = rootProject.layout,
            activeMavenRepos = setOf("maven", "debug_maven")
        )

        assertTrue(activeDefault.exists())
        assertTrue(activeDebug.exists())
        assertTrue(unrelated.exists())
        assertTrue(!staleRelease.exists())
        assertTrue(!staleLeaf.exists())
    }

    @Test
    fun `pinnable repos are filtered to materialized render plan repos`() {
        val defaultDirect = ResolvedDependency.fromId("com.example:default-only:1.0.0", "MavenRepo")
        val debugDirect = ResolvedDependency.fromId("com.example:debug-only:1.0.0", "MavenRepo")
        val unmaterializedFlavorDirect = ResolvedDependency.fromId("com.example:flavor-only:1.0.0", "MavenRepo")

        val repos = collectPinnableMavenInstallRepos(
            workspacePlan = workspacePlan(
                "maven" to listOf(defaultDirect),
                "debug_maven" to listOf(debugDirect),
                "moveit_maven" to listOf(unmaterializedFlavorDirect),
                "empty_maven" to emptyList()
            ),
            workspaceRenderPlan = WorkspaceRenderPlan(
                materializedRepoNames = setOf("maven", "debug_maven", "empty_maven")
            )
        )

        assertEquals(setOf("maven", "debug_maven"), repos.keys)
    }

    @Test
    fun `pin status probe prefers direct repo root over reachable override carrier`() {
        val directRoot = ResolvedDependency.fromId("com.example:zzz-direct-root:1.0.0", "MavenRepo")
        val overrideCarrier = ResolvedDependency.fromId("com.example:aaa-carrier:1.0.0", "MavenRepo")
            .copy(
                direct = false,
                overrideTarget = OverrideTarget(
                    artifactShortId = "com.example:aaa-carrier",
                    label = MavenDependency(group = "com.example", name = "aaa-carrier")
                )
            )

        val probeArtifact = selectPinStatusProbeArtifact(listOf(overrideCarrier, directRoot))

        assertEquals(directRoot, probeArtifact)
    }

    @Test
    fun `pinning skips non default repos with only override carriers`() {
        val workspace = rootProject.file(WORKSPACE).apply {
            writeText("")
        }
        val overrideCarrier = ResolvedDependency.fromId("com.example:covered:1.0.0", "MavenRepo")
            .copy(
                direct = false,
                overrideTarget = OverrideTarget(
                    artifactShortId = "com.example:covered",
                    label = MavenDependency(group = "com.example", name = "covered")
                )
            )

        val gradleServices = GradleServices.from(rootProject).copy(
            workerExecutor = FakeWorkerExecutor()
        )

        assertTrue("Pinning skips repos that were not generated") {
            artifactPinner.pinArtifacts(
                workspace,
                workspacePlan = workspacePlan("full_paid_debug_maven" to listOf(overrideCarrier)),
                workspaceRenderPlan = WorkspaceRenderPlan(materializedRepoNames = emptySet()),
                mavenInstallRepositoryInputs = MavenInstallRepositoryInputs(emptyMap()),
                gradleServices = gradleServices,
                logger = rootProject.logger,
            )
        }
    }

    @Test
    fun `unpinWorkspaceIfLockfilesMissing unpins when a referenced lockfile is absent`() {
        rootProject.file("maven_install.json").delete()
        rootProject.file("debug_maven_install.json").writeText("{}")
        val workspace = rootProject.file(WORKSPACE).apply {
            writeText(TWO_REPO_WORKSPACE)
        }

        val unpinned = artifactPinner.unpinWorkspaceIfLockfilesMissing(workspace)

        assertTrue("Workspace is reported as unpinned") { unpinned }
        val text = workspace.readText()
        assertTrue("maven's maven_install_json is commented") {
            "#maven_install_json = \"//:maven_install.json\"," in text
        }
        assertTrue("debug_maven's maven_install_json is commented") {
            "#maven_install_json = \"//:debug_maven_install.json\"," in text
        }
        assertTrue("maven's pinned macro load is commented") {
            """#load("@maven//:defs.bzl", maven_pinned_maven_install = "pinned_maven_install")""" in text
        }
        assertTrue("debug_maven's pinned macro load is commented") {
            """#load("@debug_maven//:defs.bzl", debug_maven_pinned_maven_install = "pinned_maven_install")""" in text
        }
        assertTrue("maven's pinned macro call is commented") {
            "#maven_pinned_maven_install()" in text
        }
        assertTrue("debug_maven's pinned macro call is commented") {
            "#debug_maven_pinned_maven_install()" in text
        }
    }

    @Test
    fun `unpinWorkspaceIfLockfilesMissing is a no-op when every referenced lockfile exists`() {
        rootProject.file("maven_install.json").writeText("{}")
        rootProject.file("debug_maven_install.json").writeText("{}")
        val workspace = rootProject.file(WORKSPACE).apply {
            writeText(TWO_REPO_WORKSPACE)
        }

        val unpinned = artifactPinner.unpinWorkspaceIfLockfilesMissing(workspace)

        assertTrue("Workspace is reported as unchanged") { !unpinned }
        assertEquals(TWO_REPO_WORKSPACE, workspace.readText())
    }

    @Test
    fun `unpinWorkspaceIfLockfilesMissing is a no-op when workspace has no active maven_install_json`() {
        val alreadyUnpinned = TWO_REPO_WORKSPACE
            .replace("maven_install_json", "#maven_install_json")
        val workspace = rootProject.file(WORKSPACE).apply {
            writeText(alreadyUnpinned)
        }

        val unpinned = artifactPinner.unpinWorkspaceIfLockfilesMissing(workspace)

        assertTrue("Workspace is reported as unchanged") { !unpinned }
        assertEquals(alreadyUnpinned, workspace.readText())
    }

    @Test
    fun `assert ensure pinning is able to recover from json corruption`() {
        rootProject.file(WORKSPACE).apply {
            writeText(
                WORKSPACE_TEMPLATE.format(
                    "\"androidx.annotation:annotation:1.2.0\",",
                    ""
                )
            )
        }
        val mavenInstall = rootProject.file("maven_install.json").apply {
            writeText(CORRUPTED_MAVEN_INSTALL_JSON)
        }

        val gradleServices = GradleServices.from(rootProject)

        assertNoThrow("Able to successfully recover from json corruption") {
            artifactPinner.ensureSafeToRun(fakeLogger, gradleServices) {
                val outputStream = BazelLogParsingOutputStream(
                    logger = fakeLogger,
                    level = LogLevel.QUIET,
                    progressLogger = NoOpProgressLogger,
                    logOutput = true
                )
                val execResult = gradleServices.execOperations.bazelCommand(
                    logger = fakeLogger,
                    "build",
                    "@maven//:androidx_annotation_annotation",
                    errorOutputStream = outputStream,
                    ignoreExit = true
                )
                outputStream to execResult
            }
        }

        assertTrue("maven_install.json is deleted") {
            !mavenInstall.exists()
        }
        val recoveredWorkspace = rootProject.file(WORKSPACE).readText()
        assertTrue("maven_install_json is commented after recovery") {
            "#maven_install_json = \"//:maven_install.json\"" in recoveredWorkspace
        }
        assertTrue("pinned macro load is commented after recovery") {
            """#load("@maven//:defs.bzl", maven_pinned_maven_install = "pinned_maven_install")""" in recoveredWorkspace
        }
        assertTrue("pinned macro call is commented after recovery") {
            "#maven_pinned_maven_install()" in recoveredWorkspace
        }
    }

    private fun workspacePlan(
        vararg repos: Pair<String, List<ResolvedDependency>>
    ): WorkspacePlan =
        WorkspacePlan(
            repoPlan = repos.associate { (repoName, pinInputs) ->
                repoName to CandidateMavenRepo(
                    kind = CandidateMavenRepoKind.AGGREGATED,
                    pinInputs = pinInputs
                )
            }
        )

    companion object {
        private val WORKSPACE_TEMPLATE = """
            load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
            http_archive(
                name = "rules_jvm_external",
                sha256 = "6274687f6fc5783b589f56a2f1ed60de3ce1f99bc4e8f9edef3de43bdf7c6e74",
                strip_prefix = "rules_jvm_external-4.3",
                url = "https://github.com/bazelbuild/rules_jvm_external/archive/4.3.zip",
            )
            
            load("@rules_jvm_external//:defs.bzl", "maven_install")
            load("@rules_jvm_external//:specs.bzl", "maven")
            
            maven_install(
                artifacts = [
                    %s
                ],
                fail_if_repin_required = False,
                fail_on_missing_checksum = False,
                %smaven_install_json = "//:maven_install.json",
                repositories = [
                    "https://dl.google.com/dl/android/maven2/",
                ],
            )
            %2${'$'}sload("@maven//:defs.bzl", maven_pinned_maven_install = "pinned_maven_install")
            %2${'$'}smaven_pinned_maven_install()""".trimIndent()

        private val MAVEN_INSTALL_JSON = """
            {
                "dependency_tree": {
                    "__AUTOGENERATED_FILE_DO_NOT_MODIFY_THIS_FILE_MANUALLY": "THERE_IS_NO_DATA_ONLY_ZUUL",
                    "__INPUT_ARTIFACTS_HASH": -22662573,
                    "__RESOLVED_ARTIFACTS_HASH": -1217984991,
                    "conflict_resolution": {},
                    "dependencies": [
                        {
                            "coord": "androidx.annotation:annotation:1.2.0",
                            "dependencies": [],
                            "directDependencies": [],
                            "file": "v1/https/dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.2.0/annotation-1.2.0.jar",
                            "mirror_urls": [
                                "https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.2.0/annotation-1.2.0.jar"
                            ],
                            "packages": [
                                "androidx.annotation"
                            ],
                            "sha256": "9029262bddce116e6d02be499e4afdba21f24c239087b76b3b57d7e98b490a36",
                            "url": "https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.2.0/annotation-1.2.0.jar"
                        }
                    ],
                    "version": "0.1.0"
                }
            }
        """.trimIndent()

        private val CORRUPTED_MAVEN_INSTALL_JSON = "{{{{}"

        private val TWO_REPO_WORKSPACE = """
            maven_install(
                artifacts = [
                    "androidx.annotation:annotation:1.2.1",
                ],
                fail_if_repin_required = False,
                fail_on_missing_checksum = False,
                maven_install_json = "//:maven_install.json",
                repositories = [
                    "https://dl.google.com/dl/android/maven2/",
                ],
            )
            load("@maven//:defs.bzl", maven_pinned_maven_install = "pinned_maven_install")
            maven_pinned_maven_install()

            maven_install(
                name = "debug_maven",
                artifacts = [
                    "androidx.annotation:annotation:1.2.1",
                ],
                fail_if_repin_required = False,
                fail_on_missing_checksum = False,
                maven_install_json = "//:debug_maven_install.json",
                repositories = [
                    "https://dl.google.com/dl/android/maven2/",
                ],
            )
            load("@debug_maven//:defs.bzl", debug_maven_pinned_maven_install = "pinned_maven_install")
            debug_maven_pinned_maven_install()
        """.trimIndent()
    }
}
