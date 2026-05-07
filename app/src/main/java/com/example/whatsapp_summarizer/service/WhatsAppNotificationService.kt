package com.example.whatsapp_summarizer.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.whatsapp_summarizer.data.database.AppDatabase
import com.example.whatsapp_summarizer.data.model.Message
import com.example.whatsapp_summarizer.data.repository.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class WhatsAppNotificationService : NotificationListenerService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var repository: MessageRepository
    
    // Track recent messages to prevent duplicates - Thread-safe
    private val recentMessages = ConcurrentHashMap<String, Long>()
    private val DEDUPLICATION_WINDOW_MS = 10000L // 10 seconds
    private val MAX_RECENT_MESSAGES = 100
    private val MAX_NAME_LENGTH = 30

    companion object {
        private const val TAG = "WhatsAppService"
        
        // Skip these exact titles
        private val SKIP_TITLES = setOf(
            "WhatsApp",
            "WhatsApp Business",
            "Checking for new messages",
            "WhatsApp Web"
        )
        
        // Skip these exact text patterns
        private val SKIP_TEXT_PATTERNS = listOf(
            "missed call",
            "incoming call",
            "You have new messages",
            "Checking for new messages",
            "WhatsApp Web",
            "joined using this device's",
            "Backup in progress",
            "Restoring messages",
            "Low on storage"
        )
        
        // Skip emoji/media indicators
        private val SKIP_EMOJI = listOf(
            "📷", "🎤", "🎵", "🎬", "📄", "📍", "👤", "🎙️", "📹", "🎞️"
        )
        
        // Regex to skip "N messages" notifications
        private val MESSAGES_COUNT_REGEX = Regex("^\\d+\\s+(new\\s+)?messages?.*", RegexOption.IGNORE_CASE)
        private val DIGIT_ONLY_REGEX = Regex("^\\d+$")
    }

    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.getDatabase(applicationContext)
        repository = MessageRepository(database.messageDao())
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        if (sbn.packageName != "com.whatsapp" && sbn.packageName != "com.whatsapp.w4b") {
            return
        }

        try {
            val notification = sbn.notification
            val extras = notification.extras

            val title = extras.getString(Notification.EXTRA_TITLE)?.trim() ?: return
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: return
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()

            // Log raw notification
            com.example.whatsapp_summarizer.util.NotificationDebugLog.add(
                com.example.whatsapp_summarizer.util.NotificationDebugLog.LogEntry(
                    timestamp = System.currentTimeMillis(),
                    rawTitle = title,
                    rawText = text,
                    parsedChatName = "",
                    parsedSender = "",
                    parsedMessage = "",
                    action = "RECEIVED",
                    reason = "Processing notification"
                )
            )

            // Skip if empty
            if (title.isBlank() || text.isBlank()) {
                logSkipped(title, text, "Empty title or text")
                return
            }
            
            // Skip system notifications
            if (title in SKIP_TITLES) {
                logSkipped(title, text, "System notification title: $title")
                return
            }
            
            // Skip "N messages" summary notifications
            if (MESSAGES_COUNT_REGEX.matches(title) || MESSAGES_COUNT_REGEX.matches(text)) {
                logSkipped(title, text, "Summary notification pattern")
                return
            }
            
            // Skip digit-only titles (like just "2" or "11")
            if (DIGIT_ONLY_REGEX.matches(title)) {
                logSkipped(title, text, "Digit-only title: $title")
                return
            }
            
            // Skip text patterns
            if (SKIP_TEXT_PATTERNS.any { text.contains(it, ignoreCase = true) }) {
                logSkipped(title, text, "Skip pattern in text")
                return
            }
            
            // Skip emoji indicators
            if (SKIP_EMOJI.any { text.contains(it) }) {
                logSkipped(title, text, "Emoji indicator")
                return
            }

            // Determine if it's a group chat
            val isGroup = isGroupChat(title, text, extras)

            // Extract chat name and sender
            val (chatName, senderName, messageContent) = parseNotification(title, text, bigText, extras, isGroup)

            if (chatName.isNotBlank() && messageContent.isNotBlank()) {
                // Normalize chat name
                val normalizedChatName = normalizeChatName(chatName)
                
                // Check for duplicates
                if (isDuplicate(normalizedChatName, senderName, messageContent)) {
                    logSkipped(title, text, "Duplicate: $normalizedChatName | $senderName", normalizedChatName, senderName, messageContent)
                    return
                }

                val message = Message(
                    chatName = normalizedChatName,
                    senderName = senderName,
                    messageContent = messageContent,
                    timestamp = System.currentTimeMillis(),
                    isGroup = isGroup
                )

                scope.launch {
                    try {
                        repository.insertMessage(message)
                        logSaved(title, text, normalizedChatName, senderName, messageContent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save message", e)
                        logSkipped(title, text, "DB Error: ${e.message}", normalizedChatName, senderName, messageContent)
                    }
                }
            } else {
                logSkipped(title, text, "Empty chatName or messageContent")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification", e)
        }
    }

    private fun logSaved(rawTitle: String, rawText: String, chatName: String, sender: String, message: String) {
        com.example.whatsapp_summarizer.util.NotificationDebugLog.add(
            com.example.whatsapp_summarizer.util.NotificationDebugLog.LogEntry(
                timestamp = System.currentTimeMillis(),
                rawTitle = rawTitle,
                rawText = rawText,
                parsedChatName = chatName,
                parsedSender = sender,
                parsedMessage = message,
                action = "SAVED",
                reason = "Message saved to database"
            )
        )
    }

    private fun logSkipped(rawTitle: String, rawText: String, reason: String, chatName: String = "", sender: String = "", message: String = "") {
        com.example.whatsapp_summarizer.util.NotificationDebugLog.add(
            com.example.whatsapp_summarizer.util.NotificationDebugLog.LogEntry(
                timestamp = System.currentTimeMillis(),
                rawTitle = rawTitle,
                rawText = rawText,
                parsedChatName = chatName,
                parsedSender = sender,
                parsedMessage = message,
                action = "SKIPPED",
                reason = reason
            )
        )
    }
    
    private fun normalizeChatName(name: String): String {
        return com.example.whatsapp_summarizer.util.ChatNameNormalizer.normalize(name)
    }
    
    private fun isDuplicate(chatName: String, senderName: String, content: String): Boolean {
        val normalizedContent = content.trim().take(100) // First 100 chars for comparison
        val normalizedChat = normalizeChatName(chatName)
        val now = System.currentTimeMillis()
        
        // Clean old entries
        val oldKeys = recentMessages.entries.filter { now - it.value > DEDUPLICATION_WINDOW_MS }
        oldKeys.forEach { recentMessages.remove(it.key) }
        
        // Check for duplicate by content within time window (regardless of sender/chat name variations)
        val contentKey = "$normalizedChat|$normalizedContent"
        if (recentMessages.containsKey(contentKey)) {
            return true
        }
        
        // Also check exact key
        val exactKey = "$chatName|$senderName|$content"
        if (recentMessages.containsKey(exactKey)) {
            return true
        }
        
        // Add both keys
        recentMessages[contentKey] = now
        recentMessages[exactKey] = now
        
        // Limit size
        while (recentMessages.size > MAX_RECENT_MESSAGES) {
            recentMessages.keys.firstOrNull()?.let { recentMessages.remove(it) }
        }
        
        return false
    }

    private fun isGroupChat(title: String, text: String, extras: android.os.Bundle): Boolean {
        val androidTextLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        
        // If text contains a colon and doesn't start with title, it's likely a group
        if (text.contains(":") && !text.startsWith(title)) {
            return true
        }
        
        // If title contains ": " it might be "Group Name: Sender Name" format
        if (title.contains(": ")) {
            return true
        }
        
        // Check if there are multiple lines (group messages often have multiple lines)
        if (androidTextLines != null && androidTextLines.size > 1) {
            return true
        }
        
        return false
    }

    private fun parseNotification(
        title: String,
        text: String,
        bigText: String?,
        extras: android.os.Bundle,
        isGroup: Boolean
    ): Triple<String, String, String> {
        var chatName = title.trim()
        var senderName = title.trim()
        var messageContent = text.trim()

        if (isGroup) {
            // Check if title is in "Group: Sender" format
            if (title.contains(": ")) {
                val colonIndex = title.indexOf(": ")
                if (colonIndex > 0) {
                    chatName = title.substring(0, colonIndex).trim()
                    senderName = title.substring(colonIndex + 2).trim()
                    messageContent = text.trim()
                    return Triple(chatName, senderName, messageContent)
                }
            }
            
            // Standard format: Title is group name, text is "Sender: message"
            chatName = title.trim()
            
            // Try to extract sender from text (format: "Sender: message")
            if (text.contains(":")) {
                val colonIndex = text.indexOf(":")
                if (colonIndex > 0 && colonIndex < MAX_NAME_LENGTH) {
                    senderName = text.substring(0, colonIndex).trim()
                    messageContent = text.substring(colonIndex + 1).trim()
                }
            } else if (!bigText.isNullOrBlank() && bigText.contains(":")) {
                val colonIndex = bigText.indexOf(":")
                if (colonIndex > 0 && colonIndex < MAX_NAME_LENGTH) {
                    senderName = bigText.substring(0, colonIndex).trim()
                }
            }
            
            // If sender is still the title, try to extract from big text or use fallback
            if (senderName == chatName) {
                if (!bigText.isNullOrBlank() && bigText.contains(":")) {
                    val colonIndex = bigText.indexOf(":")
                    if (colonIndex > 0 && colonIndex < MAX_NAME_LENGTH) {
                        senderName = bigText.substring(0, colonIndex).trim()
                    } else {
                        senderName = "Unknown"
                    }
                } else {
                    senderName = "Unknown"
                }
            }
        } else {
            // For individual chats:
            chatName = title.trim()
            senderName = title.trim()
            messageContent = text.trim()
        }

        // Clean up common WhatsApp notification artifacts
        messageContent = messageContent.replace("\\n", "\n").trim()
        
        return Triple(chatName, senderName, messageContent)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Not needed for our use case
    }
}
