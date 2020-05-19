/*
 * Copyright 2021 Grabtaxi Holdings Pte Ltd (GRAB), All rights reserved.
 *
 * Use of this source code is governed by an MIT-style license that can be found in the LICENSE file
 */

package com.grab.grazel.util

import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logger
import java.io.ByteArrayOutputStream

class LogOutputStream(
    private val logger: Logger,
    private val level: LogLevel
) : ByteArrayOutputStream() {
    override fun flush() {
        logger.log(level, toString())
        reset()
    }
}