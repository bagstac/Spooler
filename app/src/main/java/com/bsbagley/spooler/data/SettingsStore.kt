package com.bsbagley.spooler.data

import android.content.Context

/** Tiny prefs wrapper; just the Spoolman base URL for now. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var spoolmanUrl: String
        get() = prefs.getString(KEY_SPOOLMAN_URL, DEFAULT_SPOOLMAN_URL)!!
        set(value) {
            // Strip ALL whitespace, not just the ends — autocorrect likes to
            // insert a space mid-IP ("192.168. 1.171"), and whitespace is
            // never valid in a URL anyway.
            prefs.edit()
                .putString(KEY_SPOOLMAN_URL, value.filterNot(Char::isWhitespace).trimEnd('/'))
                .apply()
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

    /** On by default — writing tags is a core feature, this is an opt-out for people who never write. */
    var showWriteTag: Boolean
        get() = prefs.getBoolean(KEY_SHOW_WRITE_TAG, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_WRITE_TAG, value).apply()
        }

    /** On by default — Spoolman is a core feature, this is an opt-out for people who don't use it. */
    var spoolmanEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPOOLMAN_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SPOOLMAN_ENABLED, value).apply()
        }

    companion object {
        private const val KEY_SPOOLMAN_URL = "spoolman_url"
        private const val KEY_SHOW_RAW_DUMP = "show_raw_dump"
        private const val KEY_SHOW_HISTORY = "show_history"
        private const val KEY_SHOW_WRITE_TAG = "show_write_tag"
        private const val KEY_SPOOLMAN_ENABLED = "spoolman_enabled"

        /** Blank until the user configures their instance (settings dialog opens on first launch). */
        const val DEFAULT_SPOOLMAN_URL = ""
    }
}
