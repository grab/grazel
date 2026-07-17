/*
 * Copyright 2023 Grabtaxi Holdings PTE LTD (GRAB)
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

package com.grab.grazel.fake

import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.internal.impldep.com.google.common.collect.ImmutableList
import org.gradle.internal.impldep.com.google.common.collect.ImmutableMap
import org.gradle.internal.impldep.com.google.common.collect.ImmutableSet
import org.gradle.api.internal.artifacts.DefaultBuildIdentifier
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.artifacts.DefaultProjectComponentIdentifier
import org.gradle.api.internal.artifacts.result.DefaultResolvedComponentResult
import org.gradle.api.internal.artifacts.result.DefaultResolvedDependencyResult
import org.gradle.api.internal.artifacts.result.DefaultResolvedVariantResult
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector
import org.gradle.internal.component.external.model.ImmutableCapabilities
import org.gradle.util.Path

fun fakeComponentResult(
    group: String = "",
    name: String = "",
    version: String = "",
    isProject: Boolean = true,
    projectPath: String = "empty",
    action: DefaultResolvedComponentResult.() -> Unit = {}
): ResolvedComponentResult = DefaultResolvedComponentResult(
    /* moduleVersion = */ DefaultModuleVersionIdentifier.newId(group, name, version),
    /* selectionReason = */ FakeComponentSelectionReason(),
    /* componentId = */ when {
        isProject -> DefaultProjectComponentIdentifier(
            /* buildIdentifier = */ DefaultBuildIdentifier(Path.path(":empty")),
            /* identityPath = */ Path.path(projectPath),
            /* projectPath = */ Path.path(projectPath),
            /* projectName = */ projectPath.substringAfterLast(":")
        )

        else -> DefaultModuleComponentIdentifier.newId(
            DefaultModuleIdentifier.newId(
                /* group = */group,
                /* name = */name
            ), version
        )
    },
    /* selectedVariants = */ ImmutableMap.of(),
    /* allVariants = */ ImmutableList.of(),
    /* repositoryName = */ ""
).apply(action)

fun DefaultResolvedComponentResult.addDependencyTo(
    component: ResolvedComponentResult,
    constraint: Boolean = false,
    selectedVariantDisplayName: String = ""
) {
    val moduleVersion = component.moduleVersion!!
    val moduleIdentifier = DefaultModuleIdentifier.newId(
        /* group = */ moduleVersion.group,
        /* name = */ moduleVersion.name
    )
    addDependencies(
        ImmutableSet.of(
            DefaultResolvedDependencyResult(
            /* requested = */ DefaultModuleComponentSelector
                .newSelector(/* id = */ moduleIdentifier, /* version = */ moduleVersion.version),
            /* constraint = */ constraint,
            /* selectedComponent = */ component,
            /* selectedVariant = */ DefaultResolvedVariantResult(
                /* owner = */ DefaultModuleComponentIdentifier
                    .newId(moduleIdentifier, moduleVersion.version),
                /* displayName = */ object : DisplayName {
                    override fun getDisplayName(): String = selectedVariantDisplayName
                    override fun getCapitalizedDisplayName(): String =
                        selectedVariantDisplayName.replaceFirstChar(Char::titlecase)
                },
                /* attributes = */ FakeAttributeContainer(),
                /* capabilities = */ ImmutableCapabilities.EMPTY,
                /* externalVariant = */ null
            ),
            /* from = */ this
            )
        )
    )
}
