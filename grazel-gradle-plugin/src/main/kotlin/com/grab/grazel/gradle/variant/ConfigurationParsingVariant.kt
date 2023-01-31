package com.grab.grazel.gradle.variant

import com.grab.grazel.gradle.hasKapt
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.JvmBuild
import com.grab.grazel.gradle.variant.VariantType.Test
import com.grab.grazel.util.addTo
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer

/**
 * [Variant] extension that builds [Variant] configuration by parsing them via their names.
 *
 * Android Gradle Plugin creates configurations for permutations of build types and flavors, while
 * there are certain patterns followed when naming these variants there is no type safe API to map
 * a particular `variant` to the `configurations` belong to that variant. This class tries to parse
 * this information by manually accounting for the configuration name patterns for known configuration
 * types.
 *
 * @see AndroidVariant
 * @see AndroidBuildType
 * @see AndroidFlavor
 * @see AndroidDefaultVariant
 * @see AndroidNonVariant
 */
interface ConfigurationParsingVariant<T> : Variant<T> {

    /**
     * The base name of [Variant], this is the actual name of the variant without any type information
     * associate with it. For example, [Variant.name] of `androidTestPaidDebug` and `debug` would be\
     * `PaidDebug` and `Debug`. `androidTest` in this example is a variant type, implementing classes
     * should accordingly filter types or any non relevant data and return only the actual name
     * of the variant.
     *
     * Can return empty if baseName is not needed for parsing.
     */
    val baseName: String

    operator fun ConfigurationContainer.get(name: String) = findByName(name)

    override val variantConfigurations: Set<Configuration>
        get() = project.configurations.asSequence().filter { config ->
            val configName = config.name

            val variantNameMatches = configName.contains(name)
                || configName.contains(name.capitalize())

            val androidTestMatches = configName.contains("AndroidTest$baseName", true)
            val testMatches = configName.contains("UnitTest$baseName", true) ||
                configName.startsWith("test$baseName") ||
                configName.startsWith("kaptTest$baseName") ||
                configName.contains("TestFixtures", true) && configName.contains(baseName)

            when (variantType) {
                AndroidBuild -> !configName.isTest() && variantNameMatches
                AndroidTest -> configName.isAndroidTest() && (variantNameMatches || androidTestMatches)
                Test -> configName.isUnitTest() && (variantNameMatches || testMatches)
                else -> variantNameMatches
            }
        }.toSet()

    override val kotlinCompilerPluginConfiguration: Set<Configuration>
        get() = buildList {
            project.configurations["kotlinCompilerPluginClasspath${name.capitalize()}"]?.let(::add)
            project.configurations["kotlin-extension"]?.let(::add)
        }.toSet()

    fun parseAnnotationProcessorConfigurations(
        fallback: Configuration,
        namePattern: String = name,
        basePattern: String = baseName,
    ) = buildSet {
        if (project.hasKapt) {
            variantConfigurations.filter { configuration ->
                val configName = configuration.name
                when (variantType) {
                    AndroidBuild -> configName.startsWith("kapt${namePattern.capitalize()}")
                    AndroidTest -> configName.startsWith("kaptAndroidTest${basePattern.capitalize()}")
                    Test -> configName.startsWith("kaptTest${basePattern.capitalize()}")
                    JvmBuild -> error("Invalid variant type ${JvmBuild.name} for Android variant")
                }
            }.addTo(this)
        } else add(fallback)
    }

    fun classpathConfiguration(
        classpath: Classpath,
        namePattern: String = name,
        basePattern: String = baseName,
    ): Set<Configuration> {
        val onlyConfig = when (classpath) {
            Classpath.Runtime -> "RuntimeOnly"
            Classpath.Compile -> "CompileOnly"
        }
        val dm = "DependenciesMetadata"
        return variantConfigurations.filter {
            val configName = it.name.toLowerCase()
            when (variantType) {
                AndroidBuild -> configName == "${namePattern}${onlyConfig}$dm".toLowerCase()
                    || configName == "${namePattern}Implementation$dm".toLowerCase()

                AndroidTest -> configName == "androidTest${basePattern}${onlyConfig}$dm".toLowerCase()
                    || configName == "androidTest${basePattern}Implementation$dm".toLowerCase()

                Test -> configName == "test${basePattern}${onlyConfig}$dm".toLowerCase()
                    || configName == "test${basePattern}Implementation$dm".toLowerCase()

                else -> error("$JvmBuild invalid for build type runtime configuration")
            }
        }.toSet()
    }

    fun String.isAndroidTest() = startsWith("androidTest")
        || contains("androidTest", true)

    fun String.isUnitTest() = startsWith("test")
        || startsWith("kaptTest")
        || contains("UnitTest")

    fun String.isTest() = isAndroidTest() || isUnitTest() || contains("Test")
}