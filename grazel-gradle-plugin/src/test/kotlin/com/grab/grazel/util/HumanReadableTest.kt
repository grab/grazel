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

package com.grab.grazel.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HumanReadableTest {

    @Test
    fun `humanReadableBytes formats decimal units`() {
        assertThat(humanReadableBytes(0)).isEqualTo("0 B")
        assertThat(humanReadableBytes(512)).isEqualTo("512 B")
        assertThat(humanReadableBytes(999)).isEqualTo("999 B")
        assertThat(humanReadableBytes(1_000)).isEqualTo("1 KB")
        assertThat(humanReadableBytes(980_000)).isEqualTo("980 KB")
        assertThat(humanReadableBytes(1_000_000)).isEqualTo("1.0 MB")
        assertThat(humanReadableBytes(41_500_000)).isEqualTo("41.5 MB")
        assertThat(humanReadableBytes(1_000_000_000)).isEqualTo("1.00 GB")
        assertThat(humanReadableBytes(1_286_807_559)).isEqualTo("1.29 GB")
    }

    @Test
    fun `humanReadableDuration formats minutes seconds and millis`() {
        assertThat(humanReadableDuration(0)).isEqualTo("0ms")
        assertThat(humanReadableDuration(999)).isEqualTo("999ms")
        assertThat(humanReadableDuration(1_000)).isEqualTo("1s")
        assertThat(humanReadableDuration(45_000)).isEqualTo("45s")
        assertThat(humanReadableDuration(60_000)).isEqualTo("1m 0s")
        assertThat(humanReadableDuration(165_101)).isEqualTo("2m 45s")
    }
}
