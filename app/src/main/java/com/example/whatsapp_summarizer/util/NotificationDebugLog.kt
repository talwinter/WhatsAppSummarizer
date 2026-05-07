package com.example.whatsapp_summarizer.util

import java.text.SimpleDateFormat
import java.util.*

object NotificationDebugLog {
    
    data class LogEntry(
        val timestamp: Long,
        val rawTitle: String,
        val rawText: String,
        val parsedChatName: String,
        val parsedSender: String,
        val parsedMessage: String,
        val action: String, // "SAVED", "SKIPPED", "DUPLICATE"
        val reason: String = ""
    )
    
    private val logs = ArrayDeque<LogEntry>(100)
    private val maxSize = 50
    
    fun add(entry: LogEntry) {
        synchronized(logs) {
            if (logs.size >= maxSize) {
                logs.removeFirst()
            }
            logs.addLast(entry)
        }
    }
    
    fun getLogs(): List<LogEntry> {
        synchronized(logs) {
            return logs.toList()
        }
    }
    
    fun clear() {
        synchronized(logs) {
            logs.clear()
        }
    }
    
    fun getFormattedTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
    
    // Show invisible characters as hex
    fun showInvisibleChars(text: String): String {
        val sb = StringBuilder()
        for (char in text) {
            when {
                char == '\u00A0' -> sb.append("[NBSP]")
                char == '\u200B' -> sb.append("[ZWSP]")
                char == '\u200C' -> sb.append("[ZWNJ]")
                char == '\u200D' -> sb.append("[ZWJ]")
                char == '\u200E' -> sb.append("[LRM]")
                char == '\u200F' -> sb.append("[RLM]")
                char == '\uFEFF' -> sb.append("[BOM]")
                char == '\u2028' -> sb.append("[LS]")
                char == '\u2029' -> sb.append("[PS]")
                char == '\u202F' -> sb.append("[NNBSP]")
                char.isWhitespace() && char != ' ' -> sb.append("[W:${char.code.toString(16)}]")
                char.code < 32 -> sb.append("[C:${char.code.toString(16)}]")
                else -> sb.append(char)
            }
        }
        return sb.toString()
    }
}
