package com.shish.kaltswitch.config

import com.shish.kaltswitch.model.Filters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persisted user configuration. Versioned so we can migrate gracefully if the
 * schema changes — older configs missing fields will get defaults via
 * `ignoreUnknownKeys` / kotlinx-serialization defaults.
 */
@Serializable
data class AppConfig(
    val schemaVersion: Int = 1,
    val filters: Filters = Filters(),
)

/** Single shared JSON instance — pretty-printed so the file is hand-editable. */
val configJson: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}
