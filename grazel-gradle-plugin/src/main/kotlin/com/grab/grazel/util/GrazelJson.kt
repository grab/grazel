package com.grab.grazel.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.gradle.api.file.RegularFile
import java.io.File

// Inject?
internal val Json = Json {
    explicitNulls = false
}

internal inline fun <reified T> fromJson(file: RegularFile): T = fromJson(file.asFile)
internal inline fun <reified T> fromJson(json: File): T = json
    .inputStream()
    .buffered()
    .use { stream -> Json.decodeFromStream<T>(stream) }