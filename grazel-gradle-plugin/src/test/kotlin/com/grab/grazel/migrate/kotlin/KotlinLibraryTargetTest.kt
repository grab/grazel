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

package com.grab.grazel.migrate.kotlin

import com.grab.grazel.bazel.starlark.asString
import com.grab.grazel.bazel.starlark.statements
import com.grab.grazel.util.truth
import org.junit.Test

class KotlinLibraryTargetTest {

    @Test
    fun `emits JVM resources using kt_jvm_library resources attribute`() {
        val serviceLoaderResource =
            "src/main/resources/META-INF/services/com.android.tools.lint.client.api.IssueRegistry"

        val target = KotlinLibraryTarget(
            name = "custom-lint-rules",
            srcs = listOf("src/main/java/com/grab/lint/rules/**/*.kt"),
            deps = emptyList(),
            res = listOf(serviceLoaderResource)
        )

        val generated = statements {
            target.statements(this)
        }.asString()

        generated.truth {
            contains("resources = glob([")
            contains(serviceLoaderResource)
            doesNotContain("resource_files")
        }
    }
}
