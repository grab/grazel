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

package com.grab.grazel.util

import org.gradle.internal.logging.progress.ProgressLogger

object NoOpProgressLogger : ProgressLogger {
    override fun getDescription() = ""
    override fun setDescription(description: String?) = this
    override fun start(description: String?, status: String?) = this
    override fun started() = Unit
    override fun started(status: String?) = Unit
    override fun progress(status: String?) = Unit
    override fun progress(status: String?, failing: Boolean) = Unit
    override fun completed() = Unit
    override fun completed(status: String?, failed: Boolean) = Unit
}