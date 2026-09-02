package com.example.whatsapp_summarizer.feature.alerts

import android.content.Context

/**
 * Reads and writes the smart-alert configuration.
 *
 * The interesting part is [rules]: free text the user writes in their own words
 * ("school pickups, anything about deliveries, anyone asking a question I could
 * answer"). It goes to the model verbatim, which is why the feature can express
 * things WhatsApp's per-group mute switch cannot.
 */
class AlertSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var rules: String
        get() = prefs.getString(KEY_RULES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RULES, value).apply()

    /**
     * Watermark: only messages sent after this are considered.
     *
     * This is what stops the same message alerting twice. It works because messages
     * now carry WhatsApp's real send time rather than capture time, so the watermark
     * advances monotonically even when a notification is re-posted later.
     */
    var lastCheckedTimestamp: Long
        get() = prefs.getLong(KEY_WATERMARK, 0L)
        set(value) = prefs.edit().putLong(KEY_WATERMARK, value).apply()

    /** True once the user has given us something to match against. */
    fun isUsable(): Boolean = enabled && rules.isNotBlank()

    companion object {
        private const val KEY_ENABLED = "alerts_enabled"
        private const val KEY_RULES = "alerts_rules"
        private const val KEY_WATERMARK = "alerts_last_checked"
    }
}
