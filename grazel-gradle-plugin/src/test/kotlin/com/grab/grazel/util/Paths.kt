/*
 * Copyright 2025 Grabtaxi Holdings PTE LTD (GRAB)
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

import java.nio.file.Path
import java.nio.file.Paths


val ROOT_PATH: Path = Paths.get(System.getProperty("user.dir")).let {
    when {
        it.endsWith("grazel-gradle-plugin") -> it.parent
        else -> it
    }
}
