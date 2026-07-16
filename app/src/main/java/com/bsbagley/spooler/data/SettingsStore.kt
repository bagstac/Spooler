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

    companion object {
        private const val KEY_SPOOLMAN_URL = "spoolman_url"

        /** Blank until the user configures their instance (settings dialog opens on first launch). */
        const val DEFAULT_SPOOLMAN_URL = ""
    }
}
