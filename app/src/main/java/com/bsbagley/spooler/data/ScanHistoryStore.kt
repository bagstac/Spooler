package com.bsbagley.spooler.data

import android.content.Context
import com.bsbagley.spooler.tag.FilamentInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One persisted scan: transport info, raw dump as hex, and the decode result. */
@Serializable
data class ScanRecord(
    val timestampMillis: Long,
    val timestampIso: String,
    val uidHex: String,
    val atqaHex: String,
    val sak: Int,
    val pagesRead: Int,
    val complete: Boolean,
    /** Continuous uppercase hex of every page read, starting at page 0. */
    val hex: String,
    val filament: FilamentInfo? = null,
    val decodeError: String? = null,
)

/**
 * Dead-simple JSON-file persistence — a capped, newest-first list rewritten on
 * each add. Fine for a POC (a few KB); swap for Room if history grows real needs.
 */
class ScanHistoryStore(context: Context) {

    private val file = File(context.filesDir, "scan_history.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun loadAll(): List<ScanRecord> = runCatching {
        json.decodeFromString<List<ScanRecord>>(file.readText())
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(record: ScanRecord): List<ScanRecord> {
        val all = (listOf(record) + loadAll()).take(MAX_RECORDS)
        file.writeText(json.encodeToString(all))
        return all
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private companion object {
        const val MAX_RECORDS = 50
    }
}
