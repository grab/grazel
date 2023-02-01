package com.grab.grazel.gradle.variant

import com.grab.grazel.buildProject
import com.grab.grazel.di.GrazelComponent
import com.grab.grazel.util.addGrazelExtension
import com.grab.grazel.util.createGrazelComponent
import com.grab.grazel.util.truth
import org.gradle.api.Project
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultVariantBuilderTest {
    private lateinit var rootProject: Project
    private lateinit var androidProject: Project
    private lateinit var jvmProject: Project

    private lateinit var grazelComponent: GrazelComponent
    private lateinit var variantBuilder: VariantBuilder

    @Before
    fun setup() {
        rootProject = buildProject("root").also {
            it.addGrazelExtension()
        }
        androidProject = buildProject("android", rootProject).also {
            setupAndroidVariantProject(it)
        }
        jvmProject = buildProject("java", rootProject).also {
            setupJvmVariantProject(it)
        }

        grazelComponent = rootProject.createGrazelComponent()
        variantBuilder = grazelComponent.variantBuilder().get()
    }

    private fun assert(
        variants: List<Variant<*>>,
        size: Int,
        message: String,
        vararg items: String
    ) {
        assertEquals(
            size,
            variants.size,
            message
        )
        variants.map { it.name }
            .truth()
            .containsExactlyElementsIn(items.toList())
    }

    @Test
    fun `assert android variants are built for android project`() {
        val variants = variantBuilder.build(androidProject)
        val androidVariants = variants.filterIsInstance<AndroidVariant>()
        assertEquals(10, androidVariants.size, "Android variant are built")

        assert(
            variants = androidVariants.filter { it.variantType == VariantType.AndroidBuild },
            size = 4,
            message = "Android build variants are built",
            "paidDebug",
            "freeDebug",
            "paidRelease",
            "freeRelease"
        )

        assert(
            variants = androidVariants.filter { it.variantType == VariantType.AndroidTest },
            size = 2,
            message = "Android test variants are built",
            "paidDebugAndroidTest", "freeDebugAndroidTest"
        )

        assert(
            variants = androidVariants.filter { it.variantType == VariantType.Test },
            size = 4,
            message = "Test variants are built",
            "paidDebugUnitTest", "freeDebugUnitTest", "paidReleaseUnitTest", "freeReleaseUnitTest"
        )

        assertEquals(
            0,
            androidVariants.filter { it.variantType == VariantType.JvmBuild }.size,
            "Pure Java Variants are not built for Android projects"
        )
    }

    @Test
    fun `assert android build type variants are built`() {
        val variants = variantBuilder.build(androidProject)
        val androidVariants = variants.filterIsInstance<AndroidBuildType>()
        assert(
            variants = androidVariants.filter { it.variantType == VariantType.AndroidBuild },
            size = 2,
            message = "BuiltType build variants are built",
            "debug", "release"
        )
        assert(
            variants = androidVariants.filter { it.variantType == VariantType.Test },
            size = 2,
            message = "BuiltType build variants are built",
            "debugTest", "releaseTest"
        )

        assertEquals(
            1,
            androidVariants.filter { it.variantType == VariantType.AndroidTest }.size,
            "Only debug Android Test types are built"
        )

        assert(
            variants = androidVariants.filter { it.variantType == VariantType.AndroidTest },
            size = 1,
            message = "Only debug Android Test types are built",
            "debugAndroidTest"
        )
    }

    @Test
    fun `assert android flavor variants are built`() {
        val variants = variantBuilder.build(androidProject)
        val flavorVariants = variants.filterIsInstance<AndroidFlavor>()
        assert(
            variants = flavorVariants.filter { it.variantType == VariantType.AndroidBuild },
            size = 2,
            message = "Flavor build variants are built",
            "paid", "free"
        )
        assert(
            variants = flavorVariants.filter { it.variantType == VariantType.Test },
            size = 2,
            message = "Flavor test variants are built",
            "paidTest", "freeTest"
        )

        assert(
            variants = flavorVariants.filter { it.variantType == VariantType.AndroidTest },
            size = 2,
            message = "Flavor Android test variants are built",
            "paidAndroidTest", "freeAndroidTest"
        )

        assertEquals(
            0,
            flavorVariants.filter { it.variantType == VariantType.JvmBuild }.size,
            "Jvm variants are not built",
        )
    }

    @Test
    fun `assert jvm variants are built`() {
        val variants = variantBuilder.build(jvmProject)
        assertEquals(2, variants.size)
        assertTrue("No Android variants are built for Jvm project") {
            variants.none { it is AndroidFlavor || it is AndroidBuildType || it is AndroidVariant }
        }

        assertEquals(
            1,
            variants.filter { it.variantType == VariantType.JvmBuild }.size,
            "Jvm variants are built",
        )
        assertEquals(
            1,
            variants.filter { it.variantType == VariantType.Test }.size,
            "Jvm test variants are built",
        )
    }
}