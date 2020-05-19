/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate.android

private const val JAVA_PATTERN = "**/*.java"
private const val KOTLIN_PATTERN = "**/*.kt"
private const val ALL_PATTERN = "**"

enum class SourceSetType(val patterns: Sequence<String>) {
    JAVA(patterns = sequenceOf(JAVA_PATTERN)),
    JAVA_KOTLIN(patterns = sequenceOf(JAVA_PATTERN, KOTLIN_PATTERN)),
    KOTLIN(patterns = sequenceOf(KOTLIN_PATTERN)),
    RESOURCES(patterns = sequenceOf(ALL_PATTERN)),
    RESOURCES_CUSTOM(patterns = sequenceOf(ALL_PATTERN)),
    ASSETS(patterns = sequenceOf(ALL_PATTERN))
}