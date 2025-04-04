package com.grab.grazel.gradle.variant

import com.grab.grazel.gradle.hasKapt
import com.grab.grazel.gradle.variant.VariantType.AndroidBuild
import com.grab.grazel.gradle.variant.VariantType.AndroidTest
import com.grab.grazel.gradle.variant.VariantType.JvmBuild
import com.grab.grazel.gradle.variant.VariantType.Lint
import com.grab.grazel.gradle.variant.VariantType.Test
import com.grab.grazel.util.addTo
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer

/**
 * [Variant] extension that builds [Variant] configuration by parsing them via their names.
 *
 * Android Gradle Plugin creates configurations for permutations of build types and flavors, while
 * there are certain patterns followed when naming these variants there is no type safe API to
 * map a particular `variant` to the `configurations` belong to that variant. This class tries to
 * parse this information by manually accounting for the configuration name patterns for known
 * configuration types.
 *
 * @see AndroidVariant
 * @see AndroidBuildType
 * @see AndroidFlavor
 * @see AndroidDefaultVariant
 * @see AndroidNonVariant
 */
interface ConfigurationParsingVariant<T> : Variant<T> {

    /**
     * The base name of [Variant], this is the actual name of the variant without any type
     * information associate with it. For example, [Variant.name] of `androidTestPaidDebug` and
     * `debug` would be `PaidDebug` and `Debug`. `androidTest` in this example is a variant type,
     * implementing classes should accordingly filter types or any non relevant data and return only
     * the actual name of the variant.
     *
     * Can return empty if baseName is not needed for parsing.
     */
    val baseName: String

    operator fun ConfigurationContainer.get(name: String) = findByName(name)

    override val variantConfigurations: Set<Configuration>
        get() = project.configurations.asSequence()
            .filter { config -> matchesVariantConfiguration(config.name) }
            .toSet()

    override val kotlinCompilerPluginConfiguration: Set<Configuration>
        get() = buildList {
            val capitalizedName = name.capitalize()
            project.configurations["kotlinCompilerPluginClasspath$capitalizedName"]?.let(::add)
            project.configurations["kotlin-extension"]?.let(::add)
        }.toSet()


    /**
     * Parses the annotation processor configurations for this variant. This is a workaround for
     * Android Gradle Plugin not providing a type safe API for this.
     *
     * @param fallback The fallback configuration to use if no annotation processor configurations
     *    are found.
     * @param namePattern The name pattern to use for matching configurations. Defaults to the
     *    [Variant.name].
     * @param basePattern The base name pattern to use for matching configurations. Defaults to the
     *    [Variant.baseName].
     */
    fun parseAnnotationProcessorConfigurations(
        fallback: Configuration,
        namePattern: String = name,
        basePattern: String = baseName,
    ) = buildSet {
        if (project.hasKapt) {
            val kaptConfigurations = variantConfigurations.filter { configuration ->
                val configName = configuration.name
                val capitalizedNamePattern = namePattern.capitalize()
                val capitalizedBasePattern = basePattern.capitalize()

                when (variantType) {
                    AndroidBuild -> configName.startsWith("kapt$capitalizedNamePattern")
                    AndroidTest -> configName.startsWith("kaptAndroidTest$capitalizedBasePattern")
                    Test -> configName.startsWith("kaptTest$capitalizedBasePattern")
                    Lint -> false
                    JvmBuild -> error("Invalid variant type ${JvmBuild.name} for Android variant")
                }
            }
            kaptConfigurations.addTo(this)
        } else {
            add(fallback)
        }
    }

    fun classpathConfiguration(
        classpath: Classpath,
        namePattern: String = name,
        basePattern: String = baseName,
    ): Set<Configuration> {
        val configSuffix = when (classpath) {
            Classpath.Runtime -> "RuntimeOnly"
            Classpath.Compile -> "CompileOnly"
        }
        val metadataSuffix = "DependenciesMetadata"

        return variantConfigurations.filter { configuration ->
            val configName = configuration.name.toLowerCase()
            matchesClasspathConfiguration(
                configName,
                namePattern,
                basePattern,
                configSuffix,
                metadataSuffix
            )
        }.toSet()
    }

    /** Determines if a configuration name matches the current variant type. */
    private fun matchesVariantConfiguration(configName: String): Boolean {
        val variantNameMatches = configName.contains(name) || configName.contains(name.capitalize())

        val androidTestMatches = configName.contains("AndroidTest$baseName", true)
        val testMatches = configName.contains("UnitTest$baseName", true) ||
            configName.startsWith("test$baseName") ||
            configName.startsWith("kaptTest$baseName") ||
            (configName.contains("TestFixtures", true) && baseName in configName)

        return when (variantType) {
            AndroidBuild -> !configName.isTest() && variantNameMatches
            AndroidTest -> configName.isAndroidTest() && (variantNameMatches || androidTestMatches)
            Test -> configName.isUnitTest() && (variantNameMatches || testMatches)
            else -> variantNameMatches
        }
    }

    /**
     * Determines if a configuration name matches the classpathConfiguration for the current variant
     * type.
     */
    private fun matchesClasspathConfiguration(
        configName: String,
        namePattern: String,
        basePattern: String,
        configSuffix: String,
        metadataSuffix: String
    ): Boolean {
        return when (variantType) {
            AndroidBuild -> {
                val mainConfig = "${namePattern}${configSuffix}$metadataSuffix".toLowerCase()
                val implConfig = "${namePattern}Implementation$metadataSuffix".toLowerCase()
                configName == mainConfig || configName == implConfig
            }

            AndroidTest -> {
                val testConfig =
                    "androidTest${basePattern}${configSuffix}$metadataSuffix".toLowerCase()
                val testImplConfig =
                    "androidTest${basePattern}Implementation$metadataSuffix".toLowerCase()
                configName == testConfig || configName == testImplConfig
            }

            Test -> {
                val testConfig = "test${basePattern}${configSuffix}$metadataSuffix".toLowerCase()
                val testImplConfig = "test${basePattern}Implementation$metadataSuffix".toLowerCase()
                configName == testConfig || configName == testImplConfig
            }

            Lint -> configName == "lintChecks".toLowerCase()
            else -> error("$JvmBuild invalid for build type runtime configuration")
        }
    }

    fun String.isAndroidTest() = startsWith("androidTest") || contains("androidTest", true)

    fun String.isUnitTest() = startsWith("test") || startsWith("kaptTest") || contains("UnitTest")

    fun String.isLint() = startsWith("lintChecks")

    fun String.isTest() = isAndroidTest() || isUnitTest() || contains("Test")
}