package com.bsbagley.spooler.data

import android.content.Context

/** Tiny prefs wrapper; just the Spoolman base URL for now. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var spoolmanUrl: String
        get() = prefs.getString(KEY_SPOOLMAN_URL, DEFAULT_SPOOLMAN_URL)!!
        set(value) {
            prefs.edit().putString(KEY_SPOOLMAN_URL, value.trim().trimEnd('/')).apply()
        }

    /** Off by default — raw page hex is debug-level detail, not needed day to day. */
    var showRawDump: Boolean
        get() = prefs.getBoolean(KEY_SHOW_RAW_DUMP, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_RAW_DUMP, value).apply()
        }

    /** Off by default — keeps the main screen focused on the current scan. */
    var showHistory: Boolean
        get() = prefs.getBoolean(KEY_SHOW_HISTORY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_HISTORY, value).apply()
        }

    companion object {
        private const val KEY_SPOOLMAN_URL = "spoolman_url"
        private const val KEY_SHOW_RAW_DUMP = "show_raw_dump"
        private const val KEY_SHOW_HISTORY = "show_history"

        /** Blank until the user configures their instance (settings dialog opens on first launch). */
        const val DEFAULT_SPOOLMAN_URL = ""
    }
}
