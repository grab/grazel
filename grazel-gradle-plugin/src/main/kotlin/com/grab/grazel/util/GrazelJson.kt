package com.grab.grazel.util

import kotlinx.serialization.json.Json

// Inject?
val Json = Json {
    explicitNulls = false
}