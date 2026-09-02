package com.example.whatsapp_summarizer.feature.digest

import android.content.Context

/** Reads and writes the daily-digest configuration and its last result. */
class DigestSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Wall-clock hour the digest should arrive. Defaults to 21:00. */
    var hour: Int
        get() = prefs.getInt(KEY_HOUR, 21)
        set(value) = prefs.edit().putInt(KEY_HOUR, value).apply()

    var minute: Int
        get() = prefs.getInt(KEY_MINUTE, 0)
        set(value) = prefs.edit().putInt(KEY_MINUTE, value).apply()

    /**
     * The most recent digest text, kept so it can be re-read after the notification
     * is dismissed - otherwise the day's summary is gone with one careless swipe.
     */
    var lastDigest: String
        get() = prefs.getString(KEY_LAST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST, value).apply()

    var lastDigestAt: Long
        get() = prefs.getLong(KEY_LAST_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_AT, value).apply()

    fun formattedTime(): String = String.format("%02d:%02d", hour, minute)

    companion object {
        private const val KEY_ENABLED = "digest_enabled"
        private const val KEY_HOUR = "digest_hour"
        private const val KEY_MINUTE = "digest_minute"
        private const val KEY_LAST = "digest_last_text"
        private const val KEY_LAST_AT = "digest_last_at"
    }
}
