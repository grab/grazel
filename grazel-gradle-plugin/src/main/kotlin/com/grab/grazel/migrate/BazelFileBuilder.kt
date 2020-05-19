/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.migrate

import com.grab.grazel.bazel.starlark.Statement

interface BazelFileBuilder {
    /**
     * List of [Statement]s that this Bazel file contains
     */
    fun build(): List<Statement>
}