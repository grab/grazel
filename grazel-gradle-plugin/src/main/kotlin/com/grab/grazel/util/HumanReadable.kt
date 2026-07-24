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

import java.util.Locale

/**
 * Formats a byte count into a human-friendly decimal string (1000-based, as download tooling
 * conventionally reports transfer sizes): bytes below 1 KB stay as `B`, then `KB`, `MB`, `GB`.
 * `Locale.US` keeps the decimal separator a `.` regardless of the host locale.
 */
internal fun humanReadableBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

/**
 * Formats a duration in milliseconds into a human-friendly string: `Xm Ys` from a minute up,
 * whole `Xs` from a second up, and raw `Xms` below a second.
 */
internal fun humanReadableDuration(millis: Long): String = when {
    millis >= 60_000 -> {
        val totalSeconds = millis / 1_000
        "${totalSeconds / 60}m ${totalSeconds % 60}s"
    }
    millis >= 1_000 -> "${millis / 1_000}s"
    else -> "${millis}ms"
}
