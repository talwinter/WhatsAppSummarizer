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

            // Ask the platform's own MessagingStyle signals whether this is a group.
            val inspection = NotificationInspector.inspect(title, text, bigText, extras)
            val isGroup = inspection.isGroup
            val chatName = inspection.chatName
            val senderName = inspection.senderName
            val messageContent = inspection.messageContent
            val signals = "${inspection.signal} :: ${inspection.diagnostics}"

            // Personal conversations are never stored. Dropping them here rather than
            // filtering at query time is what keeps them out of the database entirely.
            if (!isGroup && groupsOnly()) {
                logSkipped(
                    title, text,
                    "Not a group conversation - personal chats are not captured",
                    chatName, senderName, messageContent, signals
                )
                return
            }

            if (chatName.isNotBlank() && messageContent.isNotBlank()) {
                // Normalize chat name
                val normalizedChatName = normalizeChatName(chatName)
                
                // Check for duplicates
                if (isDuplicate(normalizedChatName, senderName, messageContent)) {
                    logSkipped(title, text, "Duplicate: $normalizedChatName | $senderName", normalizedChatName, senderName, messageContent, signals)
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
                        logSaved(title, text, normalizedChatName, senderName, messageContent, signals)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save message", e)
                        logSkipped(title, text, "DB Error: ${e.message}", normalizedChatName, senderName, messageContent, signals)
                    }
                }
            } else {
                logSkipped(title, text, "Empty chatName or messageContent", signals = signals)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification", e)
        }
    }

    private fun logSaved(rawTitle: String, rawText: String, chatName: String, sender: String, message: String, signals: String = "") {
        com.example.whatsapp_summarizer.util.NotificationDebugLog.add(
            com.example.whatsapp_summarizer.util.NotificationDebugLog.LogEntry(
                timestamp = System.currentTimeMillis(),
                rawTitle = rawTitle,
                rawText = rawText,
                parsedChatName = chatName,
                parsedSender = sender,
                parsedMessage = message,
                action = "SAVED",
                reason = "Message saved to database",
                signals = signals
            )
        )
    }

    private fun logSkipped(rawTitle: String, rawText: String, reason: String, chatName: String = "", sender: String = "", message: String = "", signals: String = "") {
        com.example.whatsapp_summarizer.util.NotificationDebugLog.add(
            com.example.whatsapp_summarizer.util.NotificationDebugLog.LogEntry(
                timestamp = System.currentTimeMillis(),
                rawTitle = rawTitle,
                rawText = rawText,
                parsedChatName = chatName,
                parsedSender = sender,
                parsedMessage = message,
                action = "SKIPPED",
                reason = reason,
                signals = signals
            )
        )
    }
    
    /**
     * Whether to capture group chats only. On by default; the toggle exists so the
     * user can fall back to capturing everything if group detection ever misfires.
     */
    private fun groupsOnly(): Boolean {
        val prefs = applicationContext.getSharedPreferences(
            "app_settings", android.content.Context.MODE_PRIVATE
        )
        return prefs.getBoolean("groups_only", true)
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

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Not needed for our use case
    }
}
