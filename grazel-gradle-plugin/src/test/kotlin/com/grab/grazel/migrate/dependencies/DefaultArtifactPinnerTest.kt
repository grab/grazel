package com.grab.grazel.migrate.dependencies

import com.android.build.gradle.AppExtension
import com.grab.grazel.bazel.exec.bazelCommand
import com.grab.grazel.buildProject
import com.grab.grazel.di.GradleServices
import com.grab.grazel.fake.FakeLogger
import com.grab.grazel.fake.FakeWorkerExecutor
import com.grab.grazel.gradle.ANDROID_APPLICATION_PLUGIN
import com.grab.grazel.gradle.dependencies.model.ResolvedDependency
import com.grab.grazel.gradle.dependencies.model.WorkspaceDependencies
import com.grab.grazel.gradle.variant.DEFAULT_VARIANT
import com.grab.grazel.util.BUILD_BAZEL
import com.grab.grazel.util.NoOpProgressLogger
import com.grab.grazel.util.ROOT_PATH
import com.grab.grazel.util.WORKSPACE
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.assertNoThrow
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.doEvaluate
import com.grab.grazel.util.startOperation
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.kotlin.dsl.configure
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.io.path.copyTo
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
        rootProject.file(".bazelrc").writeText("common --enable_bzlmod=false")
        ROOT_PATH.resolve(".bazelversion").copyTo(rootProject.file(".bazelversion").toPath())

        artifactPinner = rootProject
            .createGrazelComponent()
            .artifactPinner().get() as DefaultArtifactPinner

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
                workspaceDependencies = WorkspaceDependencies(emptyMap()),
                gradleServices = gradleServices,
                parentProgress = gradleServices.progressLoggerFactory.startOperation("test"),
                logger = FakeLogger()
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

        assertTrue("Pinning is done when artifacts are actually out of date")
        {
            val gradleServices = GradleServices.from(rootProject)
            artifactPinner.shouldRunPinning(
                workspace,
                workspaceDependencies = WorkspaceDependencies(result = buildMap {
                    put(
                        DEFAULT_VARIANT,
                        listOf(ResolvedDependency.from("androidx.annotation:annotation:1.2.0:maven:false:null"))
                    )
                }),
                gradleServices = gradleServices,
                parentProgress = gradleServices.progressLoggerFactory.startOperation("test"),
                logger = rootProject.logger,
                logOutput = true
            )
        }
    }

    @Test
    fun `assert maven install json generation is successful`() {
        val workspace = rootProject.file(WORKSPACE).apply {
            writeText(
                WORKSPACE_TEMPLATE.format(
                    "\"androidx.annotation:annotation:1.1.0\",",
                    "#"
                ) // Out of date artifact
            )
        }

        rootProject.file("maven_install.json").delete()

        val gradleServices = GradleServices.from(rootProject).copy(
            workerExecutor = FakeWorkerExecutor()
        )
        assertTrue("Pinning is done and maven install json is generated") {
            artifactPinner.pinArtifacts(
                workspace,
                workspaceDependencies = WorkspaceDependencies(result = buildMap {
                    put(
                        DEFAULT_VARIANT,
                        listOf(ResolvedDependency.from("androidx.annotation:annotation:1.1.0:maven:false:null"))
                    )
                }),
                gradleServices = gradleServices,
                logger = rootProject.logger,
            )
        }
    }

    @Test
    fun `assert pinning target is chosen based on maven install json availability`() {
        assertTrue("") {
            artifactPinner.determinePinningTarget(
                rootProject.layout,
                "maven"
            ) == "@unpinned_maven//:pin"
        }
        rootProject.file("maven_install.json").delete()
        assertTrue("") {
            artifactPinner.determinePinningTarget(
                rootProject.layout,
                "maven"
            ) == "@maven//:pin"
        }
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
    }

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
            )""".trimIndent()

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
    }
}