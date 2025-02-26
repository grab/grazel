package com.grab.grazel.util

import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.gradle.api.file.RegularFile
import java.io.File
import kotlinx.serialization.json.Json as KotlinJson

// Inject?
internal val Json = KotlinJson {
    explicitNulls = false
    ignoreUnknownKeys = true
}

internal inline fun <reified T> fromJson(file: RegularFile): T = fromJson(file.asFile)
internal inline fun <reified T> fromJson(json: File): T = json
    .inputStream()
    .buffered()
    .use { stream -> Json.decodeFromStream<T>(stream) }

internal inline fun <reified T> writeJson(data: T, regularFile: RegularFile) {
    regularFile.asFile.outputStream().buffered().use { stream ->
        Json.encodeToStream(data, stream)
    }
}