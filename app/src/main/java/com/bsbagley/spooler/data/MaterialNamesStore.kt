package com.bsbagley.spooler.data

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local cache of the Open Filament Database's material name list, so the
 * Settings material-type filter doesn't need a network round trip on every
 * app launch — only the first time (or after the cache is cleared).
 */
class MaterialNamesStore(context: Context) {

    private val file = File(context.filesDir, "material_names.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun loadAll(): List<String> = runCatching {
        json.decodeFromString<List<String>>(file.readText())
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(names: List<String>) {
        file.writeText(json.encodeToString(names))
    }
}
