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

package com.grab.grazel.migrate.android

import com.grab.grazel.gradle.ConfigurationScope
import org.junit.Test

class VariantsMergerTest {

    @Test
    fun `extra build type in app and fall back for it`() {

        VariantsMergerRobo()
            .scope(ConfigurationScope.BUILD)
            .appFlavor("flavor1", "service")
            .appFlavor("flavor2", "service")
            .appBuildType("staging", "debug", "release")
            .evaluate()
            .expectedVariant(flavor = "flavor1", buildType = "debug", moduleVariantName = "debug")
            .expectedVariant(
                flavor = "flavor1",
                buildType = "release",
                moduleVariantName = "release"
            )
            .expectedVariant(flavor = "flavor1", buildType = "staging", moduleVariantName = "debug")
            .expectedVariant(flavor = "flavor2", buildType = "debug", moduleVariantName = "debug")
            .expectedVariant(
                flavor = "flavor2",
                buildType = "release",
                moduleVariantName = "release"
            )
            .expectedVariant(flavor = "flavor2", buildType = "staging", moduleVariantName = "debug")
            .verifyDepMergedVariants()
    }

    @Test
    fun `extra build type in app and no fallback`() {
        VariantsMergerRobo()
            .scope(ConfigurationScope.BUILD)
            .appFlavor("flavor1", "service")
            .appFlavor("flavor2", "service")
            .appBuildType("staging")
            .evaluate()
            .verifyDepMergedVariantsThrow(BuildTypesIsNotPresentException::class.java)
    }

    @Test
    fun `extra flavor in app module without fallback`() {
        VariantsMergerRobo()
            .scope(ConfigurationScope.BUILD)
            .appFlavor("flavor1", "service")
            .appFlavor("flavor2", "service")
            .appBuildType("staging")
            .depFlavor("flavor2", "service")
            .evaluate()
            .verifyDepMergedVariantsThrow(FlavorIsNotPresentException::class.java)
    }

    @Test
    fun `extra flavor in app module with fallback`() {
        VariantsMergerRobo()
            .scope(ConfigurationScope.BUILD)
            .appFlavor("flavor1", "service", "flavor3")
            .appFlavor("flavor2", "service", "flavor3")

            .depFlavor("flavor3", "service")
            .evaluate()
            .expectedVariant(
                flavor = "flavor1",
                buildType = "debug",
                moduleVariantName = "flavor3Debug"
            )
            .expectedVariant(
                flavor = "flavor1",
                buildType = "release",
                moduleVariantName = "flavor3Release"
            )
            .expectedVariant(
                flavor = "flavor2",
                buildType = "debug",
                moduleVariantName = "flavor3Debug"
            )
            .expectedVariant(
                flavor = "flavor2",
                buildType = "release",
                moduleVariantName = "flavor3Release"
            )
            .verifyDepMergedVariants()
    }

    @Test
    fun `extra app module`() {
        VariantsMergerRobo()
            .scope(ConfigurationScope.BUILD)
            .anotherAppModule()
            .evaluate()
            .verifyDepMergedVariantsThrow(ProjectShouldHaveOnlyOneAppModuleException::class.java)
    }

    @Test
    fun `app with no flavor`() {

        VariantsMergerRobo()
            .depFlavor("flavor1", "service")
            .scope(ConfigurationScope.BUILD)
            .evaluate()
            .verifyDepMergedVariantsThrow(VariantIsNotPresentException::class.java)
    }

}