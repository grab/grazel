/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.configuration

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.kotlin.dsl.listProperty

/**
 * Configuration for [rules_jvm_external](github.com/bazelbuild/rules_jvm_external)'s maven_install rule.
 */
data class MavenInstallConfiguration(
    private val objects: ObjectFactory,
    var resolveTimeout: Int = 600,
    var excludeArtifacts: ListProperty<String> = objects.listProperty(),
    var jetifyIncludeList: ListProperty<String> = objects.listProperty(),
    var jetifyExcludeList: ListProperty<String> = objects.listProperty()
)