/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.dozer

import com.grab.grazel.hybrid.dozerCommand
import org.gradle.api.Project

interface DozerUpdate {
    fun update(bazelDependencyAnalytics: BazelDependencyAnalytics)
}

internal fun Project.dozerCommandToTempFile(command: String) {
    dozerCommand(command, "//$TEMP_DIR_PATH:maven")
}