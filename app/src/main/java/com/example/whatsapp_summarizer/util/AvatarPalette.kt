package com.example.whatsapp_summarizer.util

import com.example.whatsapp_summarizer.R
import kotlin.math.abs

/**
 * Assigns a stable colour and initials to a name.
 *
 * Shared by the group list, the transcript and the Ask sources so one person is the
 * same colour everywhere - which is what makes a long transcript skimmable.
 */
object AvatarPalette {

    private val COLORS = intArrayOf(
        R.color.avatar_1,
        R.color.avatar_2,
        R.color.avatar_3,
        R.color.avatar_4,
        R.color.avatar_5,
        R.color.avatar_6
    )

    /** Hashed, so the same name keeps its colour between launches. */
    fun colorFor(name: String): Int = COLORS[abs(name.hashCode()) % COLORS.size]

    /** First letter of the first two words, so "Dev Team" reads as "DT". */
    fun initialsFor(name: String): String {
        val cleaned = name.trim().removePrefix("~").trim()
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(1).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }
}
