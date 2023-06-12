package com.grab.grazel.gradle.variant

import com.android.build.gradle.AppExtension
import com.android.build.gradle.api.BaseVariant
import com.google.common.truth.Truth.assertThat
import com.grab.grazel.buildProject
import com.grab.grazel.gradle.variant.Classpath.Compile
import com.grab.grazel.gradle.variant.VariantType.*
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.kotlin.dsl.the
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VariantTest {

    private lateinit var rootProject: Project
    private lateinit var androidProject: Project
    private lateinit var jvmProject: Project

    @Before
    fun setup() {
        rootProject = buildProject("root")
        androidProject = buildProject("android", rootProject)
        jvmProject = buildProject("java", rootProject)
    }

    private val appExtension get() = androidProject.the<AppExtension>()

    private fun allVariants() = appExtension.let { ext ->
        ext.applicationVariants + ext.testVariants + ext.unitTestVariants
    }.toSet()

    private fun androidVariant(baseVariant: BaseVariant) = AndroidVariant(
        androidProject,
        baseVariant
    )

    @Test
    fun `assert android variant specific configurations are parsed`() {
        setupAndroidVariantProject(androidProject)

        androidVariant(appExtension.applicationVariants.first()).let { buildVariant ->
            assertEquals(
                28,
                buildVariant.variantConfigurations.size,
                "Variant configuration parsed for build variant"
            )
            assertTrue("Variant configurations does not contain tests for build variant") {
                buildVariant.variantConfigurations.all {
                    !it.name.contains("test") || !it.name.contains("androidTest")
                }
            }
        }

        assertEquals(
            31,
            androidVariant(appExtension.testVariants.first()).variantConfigurations.size,
            "Variant configuration parsed for androidTest variant"
        )

        assertEquals(
            31,
            androidVariant(appExtension.unitTestVariants.first()).variantConfigurations.size,
            "Variant configuration parsed for unitTest variant"
        )

        val parsedConfigurations = allVariants()
            .flatMap { androidVariant(it).variantConfigurations }
            .map { it.name }
            .toSet()

        val allConfigurations = androidProject.configurations.map { it.name }

        assertEquals(
            254,
            (allConfigurations - parsedConfigurations).size,
            "Remaining unparsed configurations size at 254"
        )
    }

    @Test
    fun `assert android variant extends from are parsed`() {
        setupAndroidVariantProject(androidProject)

        androidVariant(appExtension.applicationVariants.first()).let { buildVariant ->
            assertThat(buildVariant.extendsFrom).containsExactly(
                "default",
                "debug",
                "paid"
            )
        }
        androidVariant(appExtension.testVariants.first()).let { androidTestVariant ->
            assertThat(androidTestVariant.extendsFrom).containsExactly(
                "default",
                "debug",
                "paid",
                "test",
                "debugAndroidTest"
            )
        }
        androidVariant(appExtension.unitTestVariants.first()).let { unitTestVariant ->
            assertThat(unitTestVariant.extendsFrom).containsExactly(
                "default",
                "debug",
                "paid",
                "test",
                "debugUnitTest"
            )
        }
    }


    @Test
    fun `assert android annotation processor configurations are parsed for variants`() {
        setupAndroidVariantProject(androidProject)
        fun assertAnnotationProcessorConfiguration(
            configurations: Set<Configuration>,
            name: String
        ) {
            assertTrue("$name config is parsed when kapt plugin is applied") {
                configurations.size == 1 && configurations.firstOrNull { it.name == name } != null
            }
        }

        val buildVariant = androidVariant(appExtension.applicationVariants.first())
        assertAnnotationProcessorConfiguration(
            buildVariant.annotationProcessorConfiguration,
            "kaptPaidDebug"
        )
        val androidTest = androidVariant(appExtension.testVariants.first())
        assertAnnotationProcessorConfiguration(
            androidTest.annotationProcessorConfiguration,
            "kaptAndroidTestPaidDebug"
        )
        val unitTest = androidVariant(appExtension.unitTestVariants.first())
        assertAnnotationProcessorConfiguration(
            unitTest.annotationProcessorConfiguration,
            "kaptTestPaidDebug"
        )
    }

    @Test
    fun `assert android Kotlin compiler plugin configuration is parsed for variants`() {
        setupAndroidVariantProject(androidProject)
        androidVariant(appExtension.applicationVariants.first())
            .kotlinCompilerPluginConfiguration.let {
                assertTrue("Kotlin compiler plugin classpath parsed for build variant") {
                    it.any { it.name == "kotlinCompilerPluginClasspathPaidDebug" }
                }
            }

        androidVariant(appExtension.testVariants.first())
            .kotlinCompilerPluginConfiguration.let {
                assertTrue("Kotlin compiler plugin classpath parsed for androidTest variant") {
                    it.any { it.name == "kotlinCompilerPluginClasspathPaidDebugAndroidTest" }
                }
            }

        androidVariant(appExtension.unitTestVariants.first())
            .kotlinCompilerPluginConfiguration.let {
                assertTrue("Kotlin compiler plugin classpath parsed for unitTest variant") {
                    it.any { it.name == "kotlinCompilerPluginClasspathPaidDebugUnitTest" }
                }
            }
    }


    private fun jvmVariant(project: Project, variantType: VariantType) = JvmVariant(
        jvmVariantData = JvmVariantData(
            project = project,
            variantType = variantType
        )
    )

    @Test
    fun `assert variant configurations parsed for jvm project`() {
        setupJvmVariantProject(jvmProject)
        val allConfigurations = jvmProject.configurations

        fun assertConfigurationHierarchy(
            configurationName: String,
            parsedConfigurations: Set<Configuration>,
            message: String,
        ) {
            assertTrue(message) {
                allConfigurations
                    .first { it.name == configurationName }
                    .extendsFrom.all(parsedConfigurations::contains)
            }
        }

        jvmVariant(
            jvmProject,
            variantType = JvmBuild
        ).variantConfigurations.let { configurations ->
            assertEquals(
                31,
                configurations.size,
                "Build configurations are parsed correctly for build variant"
            )
            assertConfigurationHierarchy(
                "compileClasspath",
                configurations,
                "Compile classpath configurations are parsed for build variant"
            )
            assertConfigurationHierarchy(
                "runtimeClasspath",
                configurations,
                "Runtime classpath configurations are parsed for build variant"
            )
        }
        jvmVariant(
            jvmProject,
            variantType = Test
        ).variantConfigurations.let { configurations ->
            assertEquals(
                18,
                configurations.size,
                "Build configurations are parsed correctly for test variant"
            )

            assertConfigurationHierarchy(
                "testCompileClasspath",
                configurations,
                "Compile classpath configurations are parsed for test variant"
            )
            assertConfigurationHierarchy(
                "testRuntimeClasspath",
                configurations,
                "Runtime classpath configurations are parsed for test variant"
            )
        }
    }

    @Test
    fun `assert annotation processor configurations are parsed for jvm variant`() {
        setupJvmVariantProject(jvmProject)
        fun assertAnnotationProcessorConfiguration(
            configurations: Set<Configuration>,
            name: String
        ) {
            assertTrue("$name config is parsed when kapt plugin is applied") {
                configurations.firstOrNull { it.name == name } != null
            }
        }

        val buildVariant = jvmVariant(
            jvmProject,
            variantType = JvmBuild
        )
        assertAnnotationProcessorConfiguration(
            buildVariant.annotationProcessorConfiguration,
            "kapt"
        )
        val testVariant = jvmVariant(
            jvmProject,
            variantType = Test
        )
        assertAnnotationProcessorConfiguration(
            testVariant.annotationProcessorConfiguration,
            "kaptTest"
        )
    }

    @Test
    fun `assert kotlin compiler classpath configuration parsed for jvm variant`() {
        setupJvmVariantProject(jvmProject)

        jvmVariant(
            jvmProject,
            variantType = JvmBuild
        ).kotlinCompilerPluginConfiguration.let {
            assertTrue("Kotlin compiler plugin classpath parsed for build variant") {
                it.any { it.name == "kotlinCompilerPluginClasspathMain" }
            }
        }

        jvmVariant(
            jvmProject,
            variantType = Test
        ).kotlinCompilerPluginConfiguration.let {
            assertTrue("Kotlin compiler plugin classpath parsed for test variant") {
                it.any { it.name == "kotlinCompilerPluginClasspathTest" }
            }
        }
    }

    private fun androidBuildType(
        baseVariant: BaseVariant,
        variantType: VariantType
    ) = AndroidBuildType(
        androidProject,
        baseVariant.buildType,
        variantType,
        allVariants().map { it.flavorName }.toSet()
    )

    @Test
    fun `assert android non extends from are parsed`() {
        setupAndroidVariantProject(androidProject)

        androidBuildType(
            appExtension.applicationVariants.first(),
            AndroidBuild
        ).let { buildVariant ->
            assertThat(buildVariant.extendsFrom).containsExactly(
                "default",
            )
        }
        androidBuildType(
            appExtension.testVariants.first(),
            AndroidTest
        ).let { androidTestVariant ->
            assertThat(androidTestVariant.extendsFrom).containsExactly(
                "default",
                "debug",
            )
        }
        androidBuildType(
            appExtension.unitTestVariants.first(),
            Test
        ).let { unitTestVariant ->
            assertThat(unitTestVariant.extendsFrom).containsExactly(
                "default",
                "debug",
                "test",
            )
        }
    }

    @Test
    fun `assert android build type specific variant are parsed with their configurations`() {
        setupAndroidVariantProject(androidProject)

        androidBuildType(appExtension.applicationVariants.first(), AndroidBuild).let { buildType ->
            val configurations = buildType.variantConfigurations
            assertTrue("Variant specific configurations are parsed for build type build") {
                configurations.size == 12 && configurations.all { it.name.contains("debug", true) }
            }
        }

        androidBuildType(appExtension.testVariants.first(), AndroidTest).let { buildType ->
            val configurations = buildType.variantConfigurations
            assertTrue("Variant specific configurations are parsed for build type") {
                configurations.size == 12 && configurations.all { it.name.contains("debug", true) }
            }
        }

        androidBuildType(appExtension.unitTestVariants.first(), Test).let { buildType ->
            val configurations = buildType.variantConfigurations
            assertTrue("Variant specific configurations are parsed for build type") {
                configurations.size == 24 && configurations.all { it.name.contains("debug", true) }
            }
        }
    }

    @Test
    fun `assert android build type's compileClasspath - runtimeClasspath is parsed`() {
        setupAndroidVariantProject(androidProject)

        fun assert(
            baseVariant: BaseVariant,
            variantType: VariantType,
            classpath: Classpath = Compile
        ) {
            androidBuildType(
                baseVariant,
                variantType
            ).let { buildType ->
                val configurations = when (classpath) {
                    Compile -> buildType.compileConfiguration
                    else -> buildType.runtimeConfiguration
                }
                val assertionMessage = "${classpath.name} configuration for " +
                    "${buildType.name} merges all flavor sub configurations - ${variantType.name}"
                assertEquals(2, configurations.size, assertionMessage)
                assertTrue(assertionMessage) {
                    configurations.all { it.name.contains("debug", true) }
                }
            }
        }

        Classpath.values().forEach { classpath ->
            assert(appExtension.applicationVariants.first(), AndroidBuild, classpath)
            assert(appExtension.testVariants.first(), AndroidTest, classpath)
            assert(appExtension.unitTestVariants.first(), Test, classpath)
        }
    }

    @Test
    fun `assert android build type's annotationProcessor is parsed`() {
        setupAndroidVariantProject(androidProject)

        fun assert(
            baseVariant: BaseVariant,
            variantType: VariantType,
        ) {
            androidBuildType(
                baseVariant,
                variantType
            ).let { buildType ->
                val configurations = buildType.annotationProcessorConfiguration
                val assertionMessage = "Annotation processor for ${buildType.name} merges from " +
                    "flavor sub configurations - ${variantType.name}"
                assertEquals(1, configurations.size, assertionMessage)
                assertTrue(assertionMessage) {
                    configurations.all {
                        it.name.startsWith("kapt") && it.name.endsWith("Debug")
                    }
                }
            }
        }

        assert(appExtension.applicationVariants.first(), AndroidBuild)
        assert(appExtension.testVariants.first(), AndroidTest)
        assert(appExtension.unitTestVariants.first(), Test)
    }

    @Test
    fun `assert android build type's compiler plugin configuration is parsed`() {
        setupAndroidVariantProject(androidProject)

        fun assert(
            baseVariant: BaseVariant,
            variantType: VariantType,
        ) {
            androidBuildType(
                baseVariant,
                variantType
            ).let { buildType ->
                val configurations = buildType.kotlinCompilerPluginConfiguration
                val assertion = "Kotlin compiler plugin classpath for ${buildType.name}" +
                    " parsed for ${variantType.name}"
                assertEquals(0, configurations.size, assertion)
            }
        }

        assert(
            appExtension.applicationVariants.first(),
            AndroidBuild
        )
        assert(
            appExtension.testVariants.first(),
            AndroidTest
        )
        assert(
            appExtension.unitTestVariants.first(),
            Test
        )
    }
}