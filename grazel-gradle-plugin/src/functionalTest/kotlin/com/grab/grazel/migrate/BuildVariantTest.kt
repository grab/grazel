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

package com.grab.grazel.migrate

import com.grab.grazel.BaseGrazelPluginTest
import com.grab.grazel.util.MIGRATE_DATABINDING_FLAG
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.io.File

class BuildVariantTest : BaseGrazelPluginTest() {
    private val rootProject = File("src/test/projects/android-project")

    private val workspace = File(rootProject, "WORKSPACE")
    private val appBuildBazel = File(rootProject, "app/BUILD.bazel")
    private val androidFlavorBuildBazel = File(rootProject, "android-library-flavor/BUILD.bazel")
    private val androidMismatchBuildBazel = File(rootProject, "android-library-mismatch/BUILD.bazel")
    private val dependenciesJson = File(rootProject, "build/grazel/dependencies.json")

    @Before
    fun setupTest() {
        deleteGeneratedFiles(rootProject)
    }

    @Test
    fun migrateToBazelWithFlavorsWereUsed() {
        val task = arrayOf("migrateToBazel", "bazelBuildAll", "-P${MIGRATE_DATABINDING_FLAG}")

        runGradleBuild(task, rootProject) {
            val content = androidFlavorBuildBazel.readText()
            Assert.assertTrue(isMigrateToBazelSuccessful)
            verifyBazelFilesCreated()
            sourceShouldOnlyContainEnabledFlavorAndVariant(content)
            resourceShouldOnlyContainEnabledFlavorAndVariant(content)
            moduleDepsShouldOnlyContainEnabledFlavor(content)
            compileOnlyDepsShouldUseSelectedFlavorBucket(content)
            selectedFallbackProjectDepsShouldMatchGeneratedWorkspace(
                androidFlavorBuildBazel.readText(),
                androidMismatchBuildBazel.readText(),
                workspace.readText()
            )
            sameArtifactDifferentVersionsShouldUseNearestBucket(appBuildBazel.readText())
            sameArtifactDifferentFlavorVersionsShouldUseNearestBucket(appBuildBazel.readText())
            sameArtifactDifferentTestVersionsShouldUseTestBucket(androidFlavorBuildBazel.readText())
            inheritedMainDepsShouldUseDefaultBucketInUnitTestTarget(androidFlavorBuildBazel.readText())
            sameArtifactDifferentAndroidTestVersionsShouldUseAndroidTestBucket(
                appBuildBazel.readText(),
                workspace.readText()
            )
            sameArtifactSameVersionTestAndAndroidTestExcludesShouldStayBucketScoped(
                androidFlavorBuildBazel.readText(),
                appBuildBazel.readText(),
                workspace.readText()
            )
            sameProjectTestAndAndroidTestWorkspaceExcludesShouldStayBucketScoped(
                appBuildBazel.readText(),
                workspace.readText()
            )
        }
    }

    @Test
    fun nonAppLibraryDeclaredCompileOnlyDepsUseExpectedBuckets() {
        val task = arrayOf("migrateToBazel", "-P${MIGRATE_DATABINDING_FLAG}")

        runGradleBuild(task, rootProject) {
            Assert.assertTrue(isMigrateToBazelSuccessful)
            nonAppLibraryTestCompileOnlyDepsShouldUseTestBucket(androidFlavorBuildBazel.readText())
            duplicateDeclaredCompileOnlyDepsShouldUseHighestVersion(androidFlavorBuildBazel.readText())
        }
    }

    @Test
    fun computeWorkspaceDependenciesDoesNotScheduleLegacyResolveTasksByDefault() {
        val result = runGradleBuild(
            arrayOf("computeWorkspaceDependencies", "--dry-run", "--console=plain"),
            rootProject
        )

        Assert.assertTrue(
            "Dependency resolution should collect declared metadata via a task",
            result.output.contains(":collectDeclaredDependencyMetadata SKIPPED")
        )
        Assert.assertTrue(
            "Dependency resolution should collect KSP processor metadata via a task",
            result.output.contains(":collectKspProcessorDependencies SKIPPED")
        )
        Assert.assertFalse(
            "Dependency resolution must not schedule legacy per-variant ResolveDependencies tasks",
            result.output.lines().any { it.contains("ResolveDependencies SKIPPED") }
        )
        Assert.assertTrue(
            "computeWorkspaceDependencies should still be present in the task graph",
            result.output.contains(":computeWorkspaceDependencies SKIPPED")
        )
    }

    @Test
    fun computeWorkspaceDependenciesIsUpToDateWithoutInputChanges() {
        val fixtureRoot = copyAndroidProjectFixture()

        val firstResult = runGradleBuild(
            arrayOf("computeWorkspaceDependencies", "--console=plain"),
            fixtureRoot
        )
        Assert.assertEquals(SUCCESS, firstResult.task(":computeWorkspaceDependencies")?.outcome)

        val secondResult = runGradleBuild(
            arrayOf("computeWorkspaceDependencies", "--console=plain"),
            fixtureRoot
        )
        Assert.assertEquals(
            "No-edit computeWorkspaceDependencies run should be up-to-date",
            UP_TO_DATE,
            secondResult.task(":computeWorkspaceDependencies")?.outcome
        )
    }

    @Test
    fun computeWorkspaceDependenciesInvalidatesWhenProjectDependencyDeclarationsChange() {
        val fixtureRoot = copyAndroidProjectFixture()
        val fixtureDependenciesJson = File(fixtureRoot, "build/grazel/dependencies.json")
        val fixtureMismatchBuildGradle = File(fixtureRoot, "android-library-mismatch/build.gradle")
        val originalBuildGradle = fixtureMismatchBuildGradle.readText()

        val firstResult = runGradleBuild(
            arrayOf("computeWorkspaceDependencies", "--console=plain"),
            fixtureRoot
        )
        Assert.assertEquals(SUCCESS, firstResult.task(":computeWorkspaceDependencies")?.outcome)
        Assert.assertFalse(
            "Initial selected fallback dependency data should not include free-only okio",
            fixtureDependenciesJson.readText().contains(""""shortId":"com.squareup.okio:okio"""")
        )

        fixtureMismatchBuildGradle.writeText(
            originalBuildGradle.replace(
                """    paidImplementation "com.jakewharton.timber:timber:4.7.1"""",
                """
    paidImplementation "com.jakewharton.timber:timber:4.7.1"
    paidImplementation "com.squareup.okio:okio:2.8.0"
                """.trimIndent()
            )
        )

        val secondResult = runGradleBuild(
            arrayOf("computeWorkspaceDependencies", "--console=plain"),
            fixtureRoot
        )
        Assert.assertEquals(
            "Dependency declaration edits should invalidate computeWorkspaceDependencies",
            SUCCESS,
            secondResult.task(":computeWorkspaceDependencies")?.outcome
        )
        Assert.assertTrue(
            "Updated selected fallback dependency data should include newly declared paid okio",
            fixtureDependenciesJson.readText().contains(""""shortId":"com.squareup.okio:okio"""")
        )
    }

    @Test
    fun computeWorkspaceDependenciesInvalidatesWhenProjectDependencyEdgesChange() {
        val fixtureRoot = copyAndroidProjectFixture()
        val fixtureDependenciesJson = File(fixtureRoot, "build/grazel/dependencies.json")
        val fixtureAppBuildGradle = File(fixtureRoot, "app/build.gradle")
        val originalBuildGradle = fixtureAppBuildGradle.readText()

        val firstResult = runGradleBuild(
            arrayOf("computeWorkspaceDependencies", "--console=plain"),
            fixtureRoot
        )
        Assert.assertEquals(SUCCESS, firstResult.task(":computeWorkspaceDependencies")?.outcome)
        Assert.assertTrue(
            "Initial dependency data should include Maven deps reachable through android-library-flavor",
            fixtureDependenciesJson.readText().contains(""""shortId":"com.google.dagger:dagger"""")
        )

        fixtureAppBuildGradle.writeText(
            originalBuildGradle.replace(
                """    implementation project(":android-library-flavor")""",
                """    implementation project(":android-library-mismatch")"""
            )
        )

        val secondResult = runGradleBuild(
            arrayOf("computeWorkspaceDependencies", "--console=plain"),
            fixtureRoot
        )
        Assert.assertEquals(
            "Project dependency edge edits should invalidate computeWorkspaceDependencies",
            SUCCESS,
            secondResult.task(":computeWorkspaceDependencies")?.outcome
        )
        Assert.assertFalse(
            "Updated dependency data should not include deps reachable only through the removed project edge",
            fixtureDependenciesJson.readText().contains(""""shortId":"com.google.dagger:dagger"""")
        )
    }

    @Test
    fun computeWorkspaceDependenciesInvalidatesWhenProjectDependencyExcludeRulesChange() {
        val fixtureRoot = copyAndroidProjectFixture()
        val fixtureDependenciesJson = File(fixtureRoot, "build/grazel/dependencies.json")
        val fixtureAppBuildGradle = File(fixtureRoot, "app/build.gradle")
        val originalBuildGradle = fixtureAppBuildGradle.readText()

        val firstResult = runGradleBuild(
            arrayOf("computeWorkspaceDependencies", "--console=plain"),
            fixtureRoot
        )
        Assert.assertEquals(SUCCESS, firstResult.task(":computeWorkspaceDependencies")?.outcome)
        Assert.assertTrue(
            "Initial dependency data should include Maven deps reachable through android-library-flavor",
            fixtureDependenciesJson.readText().contains(""""shortId":"com.google.dagger:dagger"""")
        )

        fixtureAppBuildGradle.writeText(
            originalBuildGradle.replace(
                """    implementation project(":android-library-flavor")""",
                """
    implementation(project(":android-library-flavor")) {
        exclude group: "com.google.dagger", module: "dagger"
    }
                """.trimIndent()
            )
        )

        val secondResult = runGradleBuild(
            arrayOf("computeWorkspaceDependencies", "--console=plain"),
            fixtureRoot
        )
        Assert.assertEquals(
            "Project dependency exclude edits should invalidate computeWorkspaceDependencies",
            SUCCESS,
            secondResult.task(":computeWorkspaceDependencies")?.outcome
        )
        Assert.assertFalse(
            "Updated dependency data should remove excluded deps reachable through the project edge",
            fixtureDependenciesJson.readText().contains(""""shortId":"com.google.dagger:dagger"""")
        )
    }

    @Test
    fun computeWorkspaceDependenciesDoesNotPromoteUnreachableNonAppImplementationDeps() {
        val fixtureRoot = copyAndroidProjectFixture()
        val fixtureDependenciesJson = File(fixtureRoot, "build/grazel/dependencies.json")
        val fixtureUnusedFlavorBuildGradle = File(fixtureRoot, "kotlin-library-flavor1/build.gradle")

        fixtureUnusedFlavorBuildGradle.appendText(
            """

dependencies {
    implementation "com.squareup.okio:okio:2.8.0"
}
            """.trimIndent()
        )

        val result = runGradleBuild(
            arrayOf("computeWorkspaceDependencies", "--console=plain"),
            fixtureRoot
        )
        Assert.assertEquals(SUCCESS, result.task(":computeWorkspaceDependencies")?.outcome)
        Assert.assertFalse(
            "Implementation deps from non-app modules unreachable from the selected binary root should not be promoted",
            fixtureDependenciesJson.readText().contains(""""shortId":"com.squareup.okio:okio"""")
        )
    }

    @Test
    fun computeWorkspaceDependenciesInvalidatesWhenKspDependencyChanges() {
        val fixtureRoot = copyAndroidProjectFixture()
        val fixtureDependenciesJson = File(fixtureRoot, "build/grazel/dependencies.json")
        val fixtureAppBuildGradle = File(fixtureRoot, "app/build.gradle")
        enableKspInFixture(fixtureRoot, moshiVersion = "1.15.0")

        val firstResult = runGradleBuild(
            arrayOf("computeWorkspaceDependencies", "--console=plain"),
            fixtureRoot
        )
        Assert.assertEquals(SUCCESS, firstResult.task(":computeWorkspaceDependencies")?.outcome)
        val initialDependencies = fixtureDependenciesJson.readText()
        Assert.assertTrue(
            "Initial KSP dependency data should include the Moshi KSP processor",
            initialDependencies.contains(""""shortId":"com.squareup.moshi:moshi-kotlin-codegen"""") &&
                initialDependencies.contains(""""version":"1.15.0"""")
        )

        fixtureAppBuildGradle.writeText(
            fixtureAppBuildGradle.readText()
                .replace(
                    """ksp 'com.squareup.moshi:moshi-kotlin-codegen:1.15.0'""",
                    """ksp 'com.squareup.moshi:moshi-kotlin-codegen:1.14.0'"""
                )
        )

        val secondResult = runGradleBuild(
            arrayOf("computeWorkspaceDependencies", "--console=plain"),
            fixtureRoot
        )
        Assert.assertEquals(
            "KSP dependency declaration edits should invalidate computeWorkspaceDependencies",
            SUCCESS,
            secondResult.task(":computeWorkspaceDependencies")?.outcome
        )
        val updatedDependencies = fixtureDependenciesJson.readText()
        Assert.assertNotEquals(
            "KSP dependency data should change after the KSP processor version changes",
            initialDependencies,
            updatedDependencies
        )
        Assert.assertTrue(
            "Updated KSP dependency data should include the changed Moshi KSP processor version",
            updatedDependencies.contains(""""shortId":"com.squareup.moshi:moshi-kotlin-codegen"""") &&
                updatedDependencies.contains(""""version":"1.14.0"""")
        )
    }

    private fun copyAndroidProjectFixture(): File {
        val fixtureRoot = File(testProjectDir.root, "android-project")
        copyFixtureFiles(rootProject, fixtureRoot)
        deleteGeneratedFiles(fixtureRoot)

        val constantsGradle = File(rootProject, "../../../../../constants.gradle")
            .canonicalFile
            .absolutePath
            .replace(File.separatorChar, '/')
        val fixtureBuildGradle = File(fixtureRoot, "build.gradle")
        fixtureBuildGradle.writeText(
            fixtureBuildGradle.readText()
                .replace(
                    """apply from: "../../../../../constants.gradle"""",
                    "apply from: \"$constantsGradle\""
                )
        )
        return fixtureRoot
    }

    private fun enableKspInFixture(fixtureRoot: File, moshiVersion: String) {
        val fixtureBuildGradle = File(fixtureRoot, "build.gradle")
        fixtureBuildGradle.writeText(
            fixtureBuildGradle.readText()
                .replace(
                    """
    repositories {
        google()
        jcenter()
    }
                    """.trimIndent(),
                    """
    repositories {
        google()
        jcenter()
        mavenCentral()
    }
                    """.trimIndent()
                )
                .replace(
                    """        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:${'$'}kotlinVersion"""",
                    """
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:${'$'}kotlinVersion"
        classpath "com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:1.9.25-1.0.20"
                    """.trimIndent()
                )
        )

        val fixtureAppBuildGradle = File(fixtureRoot, "app/build.gradle")
        fixtureAppBuildGradle.writeText(
            fixtureAppBuildGradle.readText()
                .replace(
                    "apply plugin: 'kotlin-parcelize'",
                    """
apply plugin: 'kotlin-parcelize'
apply plugin: 'com.google.devtools.ksp'
                    """.trimIndent()
                )
                .replace(
                    """    implementation 'androidx.core:core-ktx:1.3.1'""",
                    """
    implementation 'com.squareup.moshi:moshi:$moshiVersion'
    ksp 'com.squareup.moshi:moshi-kotlin-codegen:$moshiVersion'

    implementation 'androidx.core:core-ktx:1.3.1'
                    """.trimIndent()
                )
        )
    }

    private fun copyFixtureFiles(source: File, target: File) {
        if (source.name == ".gradle" || source.name == "build" || source.name.startsWith("bazel-")) {
            return
        }

        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles().orEmpty().forEach { child ->
                copyFixtureFiles(child, File(target, child.name))
            }
        } else if (source.isFile) {
            source.copyTo(target, overwrite = true)
        }
    }

    private fun deleteGeneratedFiles(projectRoot: File) {
        val mavenInstallJsons = projectRoot.listFiles { file ->
            file.name.endsWith("_maven_install.json") || file.name == "maven_install.json"
        }.orEmpty()
        arrayOf(
            File(projectRoot, "WORKSPACE"),
            File(projectRoot, "app/BUILD.bazel"),
            File(projectRoot, "android-library-flavor/BUILD.bazel"),
            File(projectRoot, "android-library-mismatch/BUILD.bazel"),
            File(projectRoot, "build/grazel/dependencies.json"),
            *mavenInstallJsons
        ).forEach { it.delete() }
    }

    private fun moduleDepsShouldOnlyContainEnabledFlavor(buildFileContent: String) {
        Assert.assertTrue(
            "Contains Kotlin library flavor2",
            buildFileContent.contains(""""//kotlin-library-flavor2"""")
        )
        Assert.assertFalse(
            "Does not contain Kotlin library flavor1",
            buildFileContent.contains(""""//kotlin-library-flavor1"""")
        )
    }

    private fun compileOnlyDepsShouldUseSelectedFlavorBucket(buildFileContent: String) {
        Assert.assertTrue(
            "Selected flavor compileOnly dependency should use the selected flavor Maven bucket",
            buildFileContent.contains(
                """"@flavor2_maven//:com_squareup_okhttp3_logging_interceptor""""
            )
        )
        Assert.assertFalse(
            "Selected flavor compileOnly dependency should not be emitted from the broad default bucket",
            buildFileContent.contains(
                """"@maven//:com_squareup_okhttp3_logging_interceptor""""
            )
        )
        Assert.assertTrue(
            "Selected build type compileOnly dependency should use the selected build type Maven bucket",
            buildFileContent.contains(
                """"@debug_maven//:com_squareup_okhttp3_okhttp_urlconnection""""
            )
        )
        Assert.assertFalse(
            "Selected build type compileOnly dependency should not be emitted from the broad default bucket",
            buildFileContent.contains(
                """"@maven//:com_squareup_okhttp3_okhttp_urlconnection""""
            )
        )
    }

    private fun selectedFallbackProjectDepsShouldMatchGeneratedWorkspace(
        androidFlavorBuildBazelContent: String,
        androidMismatchBuildBazelContent: String,
        workspaceContent: String
    ) {
        Assert.assertTrue(
            "Consumer project should use the matched fallback target",
            androidFlavorBuildBazelContent.contains(
                """"//android-library-mismatch:android-library-mismatch-flavor2-debug""""
            )
        )
        Assert.assertTrue(
            "Matched fallback target should keep its declared Maven dependency",
            Regex(""""@[^"]+//:androidx_constraintlayout_constraintlayout"""")
                .containsMatchIn(androidMismatchBuildBazelContent)
        )
        Assert.assertTrue(
            "Matched paid fallback target should keep its paid-only Maven dependency",
            Regex(""""@[^"]+//:com_jakewharton_timber_timber"""")
                .containsMatchIn(androidMismatchBuildBazelContent)
        )
        Assert.assertTrue(
            "Matched fallback target should keep selected build-type parent Maven dependency",
            Regex(""""@[^"]+//:javax_annotation_javax_annotation_api"""")
                .containsMatchIn(androidMismatchBuildBazelContent)
        )
        Assert.assertFalse(
            "Unselected free fallback Maven dependency should not be emitted on matched paid target",
            Regex(""""@[^"]+//:com_squareup_okio_okio"""")
                .containsMatchIn(androidMismatchBuildBazelContent)
        )

        val dependenciesContent = dependenciesJson.readText()
        Assert.assertTrue(
            "Workspace dependency data should include the selected paid-only fallback dependency",
            dependenciesContent.contains(""""shortId":"com.jakewharton.timber:timber"""")
        )
        Assert.assertFalse(
            "Workspace dependency data should not include the unselected free-only fallback dependency",
            dependenciesContent.contains(""""shortId":"com.squareup.okio:okio"""")
        )
        Assert.assertTrue(
            "Workspace dependency data should include the selected debug-only fallback dependency",
            dependenciesContent.contains(""""shortId":"javax.annotation:javax.annotation-api"""")
        )
        Assert.assertTrue(
            "Selected fallback build-type exclude should be preserved",
            dependenciesContent.contains(""""artifact":"selected-debug-only-exclude"""")
        )
        Assert.assertFalse(
            "Unselected release fallback exclude should not bleed into selected debug metadata",
            dependenciesContent.contains(""""artifact":"unselected-release-only-exclude"""")
        )

        val artifactRegex = Regex(
            """maven\.artifact\(\s*artifact = "constraintlayout",\s*exclusions = \[(?<exclusions>.*?)\],\s*group = "androidx\.constraintlayout",\s*version = "2\.0\.1",""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val exclusions = artifactRegex
            .find(workspaceContent)
            ?.groups
            ?.get("exclusions")
            ?.value

        Assert.assertTrue(
            "Selected fallback variant exclude should be emitted for androidx.constraintlayout:constraintlayout",
            exclusions?.contains(""""androidx.appcompat:appcompat"""") == true
        )
        Assert.assertFalse(
            "Unselected sibling fallback exclude should not bleed into androidx.constraintlayout:constraintlayout",
            exclusions?.contains(""""androidx.core:core"""") == true
        )
    }

    private fun sameArtifactDifferentVersionsShouldUseNearestBucket(appBuildFileContent: String) {
        val shortId = "org.apache.commons:commons-lang3"
        val versionsByBucket = dependencyVersionsByBucket(shortId)
        val appBinaryBlock = buildRuleBlock(appBuildFileContent, "android_binary")

        Assert.assertEquals(
            "Default bucket should keep the base declaration version",
            listOf("3.9"),
            versionsByBucket["default"].orEmpty()
        )
        Assert.assertEquals(
            "Debug bucket should keep the build-type declaration version",
            listOf("3.12.0"),
            versionsByBucket["debug"].orEmpty()
        )
        Assert.assertTrue(
            "App binary target should be generated",
            appBinaryBlock != null
        )
        Assert.assertTrue(
            "Debug target should use the build-type Maven bucket for the overridden artifact",
            appBinaryBlock!!.contains(
                """"@debug_maven//:org_apache_commons_commons_lang3""""
            )
        )
        Assert.assertFalse(
            "Debug target should not use the broad default Maven bucket for the overridden artifact",
            appBinaryBlock.contains(
                """"@maven//:org_apache_commons_commons_lang3""""
            )
        )
    }

    private fun sameArtifactDifferentFlavorVersionsShouldUseNearestBucket(appBuildFileContent: String) {
        val shortId = "com.google.code.findbugs:jsr305"
        val versionsByBucket = dependencyVersionsByBucket(shortId)
        val appBinaryBlock = buildRuleBlock(appBuildFileContent, "android_binary")

        Assert.assertEquals(
            "Default bucket should keep the base declaration version for flavor-overridden artifacts",
            listOf("3.0.1"),
            versionsByBucket["default"].orEmpty()
        )
        Assert.assertEquals(
            "Flavor bucket should keep the flavor declaration version",
            listOf("3.0.2"),
            versionsByBucket["flavor2"].orEmpty()
        )
        Assert.assertTrue(
            "App binary target should be generated",
            appBinaryBlock != null
        )
        Assert.assertTrue(
            "Flavor target should use the flavor Maven bucket for the overridden artifact",
            appBinaryBlock!!.contains(
                """"@flavor2_maven//:com_google_code_findbugs_jsr305""""
            )
        )
        Assert.assertFalse(
            "Flavor target should not use the broad default Maven bucket for the overridden artifact",
            appBinaryBlock.contains(
                """"@maven//:com_google_code_findbugs_jsr305""""
            )
        )
    }

    private fun sameArtifactDifferentTestVersionsShouldUseTestBucket(appBuildFileContent: String) {
        val shortId = "com.google.j2objc:j2objc-annotations"
        val versionsByBucket = dependencyVersionsByBucket(shortId)
        val unitTestBlock = buildRuleBlock(appBuildFileContent, "android_unit_test")

        Assert.assertTrue(
            "App unit test target should be generated",
            unitTestBlock != null
        )
        Assert.assertEquals(
            "Default bucket should keep the main declaration version for test-overridden artifacts",
            listOf("1.1"),
            versionsByBucket["default"].orEmpty()
        )
        Assert.assertEquals(
            "Test bucket should keep the direct test declaration version",
            listOf("1.3"),
            versionsByBucket["test"].orEmpty()
        )
        Assert.assertTrue(
            "Unit test target should use the test Maven bucket for a direct test version override",
            unitTestBlock!!.contains(
                """"@test_maven//:com_google_j2objc_j2objc_annotations""""
            )
        )
        Assert.assertFalse(
            "Unit test target should not use the broad default Maven bucket for a direct test version override",
            unitTestBlock.contains(
                """"@maven//:com_google_j2objc_j2objc_annotations""""
            )
        )
    }

    private fun inheritedMainDepsShouldUseDefaultBucketInUnitTestTarget(buildFileContent: String) {
        val unitTestBlock = buildRuleBlock(buildFileContent, "android_unit_test")

        Assert.assertTrue(
            "Library unit test target should be generated",
            unitTestBlock != null
        )
        Assert.assertTrue(
            "Inherited main dependency should use the default Maven bucket in unit tests",
            unitTestBlock!!.contains(
                """"@maven//:androidx_appcompat_appcompat""""
            )
        )
        Assert.assertFalse(
            "Inherited main dependency should not be relabeled into the broad test Maven bucket",
            unitTestBlock.contains(
                """"@test_maven//:androidx_appcompat_appcompat""""
            )
        )
    }

    private fun sameArtifactDifferentAndroidTestVersionsShouldUseAndroidTestBucket(
        appBuildFileContent: String,
        workspaceContent: String
    ) {
        val shortId = "com.google.j2objc:j2objc-annotations"
        val versionsByBucket = dependencyVersionsByBucket(shortId)
        val appBinaryBlock = buildRuleBlock(appBuildFileContent, "android_binary")
        val instrumentationTestBlock = buildRuleBlock(appBuildFileContent, "android_instrumentation_binary")
        val defaultArtifactBlock = mavenArtifactBlock(
            workspaceContent = workspaceContent,
            repoName = "maven",
            group = "com.google.j2objc",
            artifact = "j2objc-annotations",
            version = "1.1"
        )
        val androidTestArtifactBlock = mavenArtifactBlock(
            workspaceContent = workspaceContent,
            repoName = "android_test_maven",
            group = "com.google.j2objc",
            artifact = "j2objc-annotations",
            version = "1.3"
        )

        Assert.assertTrue(
            "App binary target should be generated",
            appBinaryBlock != null
        )
        Assert.assertTrue(
            "App instrumentation test target should be generated",
            instrumentationTestBlock != null
        )
        Assert.assertEquals(
            "Default bucket should keep the main declaration version for androidTest-overridden artifacts",
            listOf("1.1"),
            versionsByBucket["default"].orEmpty()
        )
        Assert.assertEquals(
            "AndroidTest bucket should keep the direct androidTest declaration version",
            listOf("1.3"),
            versionsByBucket["androidTest"].orEmpty()
        )
        Assert.assertTrue(
            "App binary target should use the default Maven bucket for the main declaration",
            appBinaryBlock!!.contains(
                """"@maven//:com_google_j2objc_j2objc_annotations""""
            )
        )
        Assert.assertTrue(
            "Instrumentation target should use the androidTest Maven bucket for a direct androidTest version override",
            instrumentationTestBlock!!.contains(
                """"@android_test_maven//:com_google_j2objc_j2objc_annotations""""
            )
        )
        Assert.assertFalse(
            "Instrumentation target should not use the broad default Maven bucket for a direct androidTest version override",
            instrumentationTestBlock.contains(
                """"@maven//:com_google_j2objc_j2objc_annotations""""
            )
        )
        Assert.assertTrue(
            "Inherited main dependency should use the default Maven bucket in instrumentation tests",
            instrumentationTestBlock.contains(
                """"@maven//:androidx_appcompat_appcompat""""
            )
        )
        Assert.assertFalse(
            "Inherited main dependency should not be relabeled into the broad androidTest Maven bucket",
            instrumentationTestBlock.contains(
                """"@android_test_maven//:androidx_appcompat_appcompat""""
            )
        )
        Assert.assertTrue(
            "Default Maven artifact should carry the main declaration exclude",
            defaultArtifactBlock?.contains(""""com.example:main-only-exclude"""") == true
        )
        Assert.assertFalse(
            "Default Maven artifact should not carry the androidTest declaration exclude",
            defaultArtifactBlock?.contains(""""com.example:android-test-only-exclude"""") == true
        )
        Assert.assertTrue(
            "AndroidTest Maven artifact should carry the androidTest declaration exclude",
            androidTestArtifactBlock?.contains(""""com.example:android-test-only-exclude"""") == true
        )
        Assert.assertFalse(
            "AndroidTest Maven artifact should not carry the main declaration exclude",
            androidTestArtifactBlock?.contains(""""com.example:main-only-exclude"""") == true
        )
    }

    private fun sameArtifactSameVersionTestAndAndroidTestExcludesShouldStayBucketScoped(
        unitTestBuildFileContent: String,
        appBuildFileContent: String,
        workspaceContent: String
    ) {
        val shortId = "org.hamcrest:hamcrest-library"
        val versionsByBucket = dependencyVersionsByBucket(shortId)
        val unitTestBlock = buildRuleBlock(unitTestBuildFileContent, "android_unit_test")
        val instrumentationTestBlock = buildRuleBlock(appBuildFileContent, "android_instrumentation_binary")
        val testArtifactBlock = mavenArtifactBlock(
            workspaceContent = workspaceContent,
            repoName = "test_maven",
            group = "org.hamcrest",
            artifact = "hamcrest-library",
            version = "1.3"
        )
        val androidTestArtifactBlock = mavenArtifactBlock(
            workspaceContent = workspaceContent,
            repoName = "android_test_maven",
            group = "org.hamcrest",
            artifact = "hamcrest-library",
            version = "1.3"
        )

        Assert.assertTrue(
            "Library unit test target should be generated",
            unitTestBlock != null
        )
        Assert.assertTrue(
            "App instrumentation test target should be generated",
            instrumentationTestBlock != null
        )
        Assert.assertEquals(
            "Test bucket should keep the direct test declaration version",
            listOf("1.3"),
            versionsByBucket["test"].orEmpty()
        )
        Assert.assertEquals(
            "AndroidTest bucket should keep the direct androidTest declaration version",
            listOf("1.3"),
            versionsByBucket["androidTest"].orEmpty()
        )
        Assert.assertTrue(
            "Unit test target should use the test Maven bucket for direct test-only metadata",
            unitTestBlock!!.contains(
                """"@test_maven//:org_hamcrest_hamcrest_library""""
            )
        )
        Assert.assertTrue(
            "Instrumentation target should use the androidTest Maven bucket for direct androidTest-only metadata",
            instrumentationTestBlock!!.contains(
                """"@android_test_maven//:org_hamcrest_hamcrest_library""""
            )
        )
        Assert.assertTrue(
            "Test Maven artifact should carry the test declaration exclude",
            testArtifactBlock?.contains(""""org.hamcrest:hamcrest-core"""") == true
        )
        Assert.assertFalse(
            "Test Maven artifact should not carry the androidTest declaration exclude",
            testArtifactBlock?.contains(""""com.example:android-test-only-hamcrest-exclude"""") == true
        )
        Assert.assertTrue(
            "AndroidTest Maven artifact should carry the androidTest declaration exclude",
            androidTestArtifactBlock?.contains(""""com.example:android-test-only-hamcrest-exclude"""") == true
        )
        Assert.assertFalse(
            "AndroidTest Maven artifact should not carry the test declaration exclude",
            androidTestArtifactBlock?.contains(""""org.hamcrest:hamcrest-core"""") == true
        )
    }

    private fun sameProjectTestAndAndroidTestWorkspaceExcludesShouldStayBucketScoped(
        appBuildFileContent: String,
        workspaceContent: String
    ) {
        val shortId = "commons-io:commons-io"
        val versionsByBucket = dependencyVersionsByBucket(shortId)
        val instrumentationTestBlock = buildRuleBlock(appBuildFileContent, "android_instrumentation_binary")
        val testArtifactBlock = mavenArtifactBlock(
            workspaceContent = workspaceContent,
            repoName = "test_maven",
            group = "commons-io",
            artifact = "commons-io",
            version = "2.11.0"
        )
        val androidTestArtifactBlock = mavenArtifactBlock(
            workspaceContent = workspaceContent,
            repoName = "android_test_maven",
            group = "commons-io",
            artifact = "commons-io",
            version = "2.11.0"
        )

        Assert.assertTrue(
            "App instrumentation test target should be generated",
            instrumentationTestBlock != null
        )
        Assert.assertEquals(
            "Same-project test bucket should keep the direct test declaration version",
            listOf("2.11.0"),
            versionsByBucket["test"].orEmpty()
        )
        Assert.assertEquals(
            "Same-project androidTest bucket should keep the direct androidTest declaration version",
            listOf("2.11.0"),
            versionsByBucket["androidTest"].orEmpty()
        )
        Assert.assertTrue(
            "App instrumentation target should use the androidTest Maven bucket for direct androidTest metadata",
            instrumentationTestBlock!!.contains(
                """"@android_test_maven//:commons_io_commons_io""""
            )
        )
        Assert.assertTrue(
            "Same-project test Maven artifact should carry the test declaration exclude",
            testArtifactBlock?.contains(""""com.example:same-project-test-only-exclude"""") == true
        )
        Assert.assertFalse(
            "Same-project test Maven artifact should not carry the androidTest declaration exclude",
            testArtifactBlock?.contains(""""com.example:same-project-android-test-only-exclude"""") == true
        )
        Assert.assertTrue(
            "Same-project androidTest Maven artifact should carry the androidTest declaration exclude",
            androidTestArtifactBlock
                ?.contains(""""com.example:same-project-android-test-only-exclude"""") == true
        )
        Assert.assertFalse(
            "Same-project androidTest Maven artifact should not carry the test declaration exclude",
            androidTestArtifactBlock?.contains(""""com.example:same-project-test-only-exclude"""") == true
        )
    }

    private fun nonAppLibraryTestCompileOnlyDepsShouldUseTestBucket(buildFileContent: String) {
        val shortId = "org.apache.commons:commons-text"
        val versionsByBucket = dependencyVersionsByBucket(shortId)
        val unitTestBlock = buildRuleBlock(buildFileContent, "android_unit_test")

        Assert.assertTrue(
            "Non-app library unit test target should be generated",
            unitTestBlock != null
        )
        Assert.assertEquals(
            "Non-app library testCompileOnly dependency should be stored in the test bucket",
            listOf("1.10.0"),
            versionsByBucket["test"].orEmpty()
        )
        Assert.assertFalse(
            "Non-app library testCompileOnly dependency should not be stored in the default bucket",
            versionsByBucket.containsKey("default")
        )
        Assert.assertTrue(
            "Non-app library testCompileOnly dependency should use the test Maven bucket",
            unitTestBlock!!.contains(
                """"@test_maven//:org_apache_commons_commons_text""""
            )
        )
        Assert.assertFalse(
            "Non-app library testCompileOnly dependency should not use the broad default Maven bucket",
            unitTestBlock.contains(
                """"@maven//:org_apache_commons_commons_text""""
            )
        )
    }

    private fun duplicateDeclaredCompileOnlyDepsShouldUseHighestVersion(buildFileContent: String) {
        val shortId = "org.apache.commons:commons-collections4"
        val versionsByBucket = dependencyVersionsByBucket(shortId)

        Assert.assertEquals(
            "Declared compileOnly conflicts in one bucket should keep Gradle-style highest version",
            listOf("4.4"),
            versionsByBucket["debug"].orEmpty()
        )
        Assert.assertFalse(
            "Declared compileOnly conflicts should not fall back to the broad default bucket",
            versionsByBucket.containsKey("default")
        )
        Assert.assertTrue(
            "Declared compileOnly conflict winner should still use the selected build type Maven bucket",
            buildFileContent.contains(
                """"@debug_maven//:org_apache_commons_commons_collections4""""
            )
        )
        Assert.assertFalse(
            "Declared compileOnly conflict winner should not use the broad default Maven bucket",
            buildFileContent.contains(
                """"@maven//:org_apache_commons_commons_collections4""""
            )
        )
    }

    private fun buildRuleBlock(buildFileContent: String, ruleName: String): String? {
        val start = buildFileContent.indexOf("$ruleName(")
        if (start < 0) return null

        return balancedRuleBlock(buildFileContent, start)
    }

    private fun mavenArtifactBlock(
        workspaceContent: String,
        repoName: String,
        group: String,
        artifact: String,
        version: String
    ): String? {
        var searchFrom = 0
        while (true) {
            val start = workspaceContent.indexOf("maven_install(", searchFrom)
            if (start < 0) return null
            val installBlock = balancedRuleBlock(workspaceContent, start) ?: return null
            if (installBlock.contains("""name = "$repoName"""")) {
                val artifactRegex = Regex(
                    """maven\.artifact\(\s*artifact = "$artifact",\s*exclusions = \[(?<exclusions>.*?)\],\s*group = "$group",\s*version = "$version",""",
                    setOf(RegexOption.DOT_MATCHES_ALL)
                )
                return artifactRegex.find(installBlock)?.value
            }
            searchFrom = start + 1
        }
    }

    private fun balancedRuleBlock(content: String, start: Int): String? {
        var depth = 0
        for (index in start until content.length) {
            when (content[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        return content.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun dependencyVersionsByBucket(shortId: String): Map<String, List<String>> {
        val result = Json.parseToJsonElement(dependenciesJson.readText())
            .jsonObject
            .getValue("result")
            .jsonObject

        return result.mapValues { (_, dependencies) ->
            dependencies.jsonArray
                .mapNotNull { dependency ->
                    val dependencyObject = dependency.jsonObject
                    if (dependencyObject["shortId"]?.jsonPrimitive?.contentOrNull == shortId) {
                        dependencyObject["version"]?.jsonPrimitive?.contentOrNull
                    } else {
                        null
                    }
                }
        }.filterValues { it.isNotEmpty() }
    }

    private fun sourceShouldOnlyContainEnabledFlavorAndVariant(buildFileContent: String) {
        Assert.assertTrue(
            "Contains Kotlin library flavor2 sources",
            buildFileContent.contains("""src/flavor2/java/com/grab/grazel/android/flavor""")
        )

        Assert.assertFalse(
            "Does not Kotlin library flavor1 sources",
            buildFileContent.contains("""src/flavor1/java/com/grab/grazel/android/flavor""")
        )

        Assert.assertTrue(
            "Contains Kotlin library main sources",
            buildFileContent.contains("""src/main/java/com/grab/grazel/android/flavor""")
        )
    }

    private fun resourceShouldOnlyContainEnabledFlavorAndVariant(buildFileContent: String) {
        Assert.assertTrue(
            "Contains flavor2 resources",
            buildFileContent.contains(""""res": "src/flavor2/res"""")
        )
        Assert.assertFalse(
            "Does not contain flavor1 resources",
            buildFileContent.contains("""src/flavor1/res""")
        )
        Assert.assertTrue(
            "Contains main resources",
            buildFileContent.contains(""""res": "src/main/res"""")
        )
    }

    private fun verifyBazelFilesCreated() {
        Assert.assertTrue(workspace.exists())
        Assert.assertTrue(appBuildBazel.exists())
        Assert.assertTrue(androidFlavorBuildBazel.exists())
        Assert.assertTrue(androidMismatchBuildBazel.exists())
    }
}
