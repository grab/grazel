/*
 * Copyright 2021 Grabtaxi Holdings PTE LTE (GRAB)
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

package com.grab.grazel.util

internal const val BAZEL_ENABLED = "bazelEnabled"

// Name of kt_android_library's aar target
internal const val KT_INTERMEDIATE_TARGET_SUFFIX = "_base"

// TASK NAMES
internal const val BAZEL_BUILD_ALL_TASK_NAME = "bazelBuildAll"
internal const val BAZEL_CLEAN_TASK_NAME = "bazelClean"

// BAZEL FILE NAMES
internal const val WORKSPACE = "WORKSPACE"
internal const val BUILD_BAZEL = "BUILD.bazel"
internal const val BUILD_BAZEL_IGNORE = "BUILD.bazelignore"