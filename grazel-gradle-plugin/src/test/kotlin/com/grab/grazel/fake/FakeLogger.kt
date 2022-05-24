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

package com.grab.grazel.fake

import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logger
import org.slf4j.Marker

open class FakeLogger : Logger {

    override fun getName(): String {
        return "Fake"
    }

    override fun isTraceEnabled(): Boolean {
        return false
    }

    override fun isTraceEnabled(p0: Marker?): Boolean {
        return true
    }

    override fun trace(p0: String?) {
    }

    override fun trace(p0: String?, p1: Any?) {
    }

    override fun trace(p0: String?, p1: Any?, p2: Any?) {
    }

    override fun trace(p0: String?, vararg p1: Any?) {
    }

    override fun trace(p0: String?, p1: Throwable?) {
    }

    override fun trace(p0: Marker?, p1: String?) {
    }

    override fun trace(p0: Marker?, p1: String?, p2: Any?) {
    }

    override fun trace(p0: Marker?, p1: String?, p2: Any?, p3: Any?) {
    }

    override fun trace(p0: Marker?, p1: String?, vararg p2: Any?) {
    }

    override fun trace(p0: Marker?, p1: String?, p2: Throwable?) {
    }

    override fun isDebugEnabled(): Boolean {
        return true
    }

    override fun isDebugEnabled(p0: Marker?): Boolean {
        return true
    }

    override fun debug(message: String?, vararg objects: Any?) {
    }

    override fun debug(p0: String?) {
    }

    override fun debug(p0: String?, p1: Any?) {
    }

    override fun debug(p0: String?, p1: Any?, p2: Any?) {
    }

    override fun debug(p0: String?, p1: Throwable?) {
    }

    override fun debug(p0: Marker?, p1: String?) {
    }

    override fun debug(p0: Marker?, p1: String?, p2: Any?) {
    }

    override fun debug(p0: Marker?, p1: String?, p2: Any?, p3: Any?) {
    }

    override fun debug(p0: Marker?, p1: String?, vararg p2: Any?) {
    }

    override fun debug(p0: Marker?, p1: String?, p2: Throwable?) {
    }

    override fun isInfoEnabled(): Boolean {
        return true
    }

    override fun isInfoEnabled(p0: Marker?): Boolean {
        return true
    }

    override fun info(message: String?, vararg objects: Any?) {
    }

    override fun info(p0: String?) {
    }

    override fun info(p0: String?, p1: Any?) {
    }

    override fun info(p0: String?, p1: Any?, p2: Any?) {
    }

    override fun info(p0: String?, p1: Throwable?) {
    }

    override fun info(p0: Marker?, p1: String?) {
    }

    override fun info(p0: Marker?, p1: String?, p2: Any?) {
    }

    override fun info(p0: Marker?, p1: String?, p2: Any?, p3: Any?) {
    }

    override fun info(p0: Marker?, p1: String?, vararg p2: Any?) {
    }

    override fun info(p0: Marker?, p1: String?, p2: Throwable?) {
    }

    override fun isWarnEnabled(): Boolean {
        return true
    }

    override fun isWarnEnabled(p0: Marker?): Boolean {
        return true
    }

    override fun warn(p0: String?) {
    }

    override fun warn(p0: String?, p1: Any?) {
    }

    override fun warn(p0: String?, vararg p1: Any?) {
    }

    override fun warn(p0: String?, p1: Any?, p2: Any?) {
    }

    override fun warn(p0: String?, p1: Throwable?) {
    }

    override fun warn(p0: Marker?, p1: String?) {
    }

    override fun warn(p0: Marker?, p1: String?, p2: Any?) {
    }

    override fun warn(p0: Marker?, p1: String?, p2: Any?, p3: Any?) {
    }

    override fun warn(p0: Marker?, p1: String?, vararg p2: Any?) {
    }

    override fun warn(p0: Marker?, p1: String?, p2: Throwable?) {
    }

    override fun isErrorEnabled(): Boolean {
        return true
    }

    override fun isErrorEnabled(p0: Marker?): Boolean {
        return true
    }

    override fun error(p0: String?) {
    }

    override fun error(p0: String?, p1: Any?) {
    }

    override fun error(p0: String?, p1: Any?, p2: Any?) {
    }

    override fun error(p0: String?, vararg p1: Any?) {
    }

    override fun error(p0: String?, p1: Throwable?) {
    }

    override fun error(p0: Marker?, p1: String?) {
    }

    override fun error(p0: Marker?, p1: String?, p2: Any?) {
    }

    override fun error(p0: Marker?, p1: String?, p2: Any?, p3: Any?) {
    }

    override fun error(p0: Marker?, p1: String?, vararg p2: Any?) {
    }

    override fun error(p0: Marker?, p1: String?, p2: Throwable?) {
    }

    override fun isLifecycleEnabled(): Boolean {
        return true
    }

    override fun lifecycle(message: String?) {
    }

    override fun lifecycle(message: String?, vararg objects: Any?) {
    }

    override fun lifecycle(message: String?, throwable: Throwable?) {
    }

    override fun isQuietEnabled(): Boolean {
        return true
    }

    override fun quiet(message: String?) {
    }

    override fun quiet(message: String?, vararg objects: Any?) {
    }

    override fun quiet(message: String?, throwable: Throwable?) {
    }

    override fun isEnabled(level: LogLevel?): Boolean {
        return true
    }

    data class LogStatement(
        val level: LogLevel?,
        val message: String?,
        val throwable: Throwable? = null
    )

    val logs = mutableListOf<LogStatement>()

    override fun log(level: LogLevel?, message: String?) {
        logs += LogStatement(level, message)
    }

    override fun log(level: LogLevel?, message: String?, vararg objects: Any?) {
    }

    override fun log(level: LogLevel?, message: String?, throwable: Throwable?) {
        logs += LogStatement(level, message, throwable)
    }
}