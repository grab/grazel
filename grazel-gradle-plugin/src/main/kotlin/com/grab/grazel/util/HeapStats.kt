package com.grab.grazel.util

import org.gradle.api.logging.Logger

/**
 * Logs current JVM heap usage. Only logs when `-Dgrazel.logHeap=true` is set.
 */
internal fun Logger.logHeap(phase: String) {
    if (System.getProperty("grazel.logHeap") != "true") return
    val runtime = Runtime.getRuntime()
    val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    val maxMb = runtime.maxMemory() / (1024 * 1024)
    val pct = if (maxMb > 0) usedMb * 100 / maxMb else 0
    lifecycle("Grazel: [$phase] heap: used ${formatMb(usedMb)} / max ${formatMb(maxMb)} ($pct%)")
}

private fun formatMb(mb: Long): String = when {
    mb >= 1024 -> "%.1fG".format(mb / 1024.0)
    else -> "${mb}M"
}