package com.example.whatsapp_summarizer.feature.alerts

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.whatsapp_summarizer.WhatsAppSummarizerApp
import com.example.whatsapp_summarizer.ai.AiClient
import com.example.whatsapp_summarizer.data.model.Message
import com.example.whatsapp_summarizer.ui.group.ChatActivity
import com.example.whatsapp_summarizer.util.Notifier
import com.example.whatsapp_summarizer.util.SecureStorage
import java.util.concurrent.TimeUnit

/**
 * Decides whether anything that just arrived is worth interrupting the user for.
 *
 * WhatsApp can only mute a whole group. This asks the model, per batch of new
 * messages, whether any of them match what the user said they care about - so a
 * silenced group can still surface the one message that matters.
 *
 * Runs debounced rather than per message: [schedule] replaces any pending run, so a
 * burst of twenty messages costs one model call instead of twenty.
 */
class AlertWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val settings = AlertSettings(context)

        if (!settings.isUsable()) return Result.success()

        val apiKey = SecureStorage(context).getApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Alerts enabled but no API key configured")
            return Result.success()
        }
        // Nothing to do if we cannot reach the user anyway.
        if (!Notifier.canPost(context)) {
            Log.w(TAG, "Alerts enabled but notifications are not permitted")
            return Result.success()
        }

        val repository = (context as WhatsAppSummarizerApp).repository

        // First ever run: set the watermark to now rather than alerting on the whole
        // backlog, which would be a wall of notifications about stale messages.
        if (settings.lastCheckedTimestamp == 0L) {
            settings.lastCheckedTimestamp = System.currentTimeMillis()
            return Result.success()
        }

        val newMessages = repository.getGroupMessagesSince(settings.lastCheckedTimestamp)
        if (newMessages.isEmpty()) return Result.success()

        // Advance the watermark before calling out. If the request fails we would
        // rather skip a batch than re-alert on messages the user already saw.
        val newest = newMessages.maxOf { it.timestamp }
        settings.lastCheckedTimestamp = newest

        val candidates = newMessages.take(MAX_MESSAGES_PER_BATCH)

        return try {
            val matches = findMatches(apiKey, settings.rules, candidates)
            matches.forEach { match ->
                val message = candidates.getOrNull(match.index) ?: return@forEach
                Notifier.postAlert(
                    context = context,
                    title = message.chatName,
                    body = "${message.senderName}: ${message.messageContent}\n\n→ ${match.reason}",
                    intent = chatIntent(context, message.chatName)
                )
            }
            Log.i(TAG, "Checked ${candidates.size} messages, alerted on ${matches.size}")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Alert check failed", e)
            // Not retried: by now the watermark has moved on, and a stale alert is
            // worse than a missed one for this feature.
            Result.success()
        }
    }

    private data class Match(val index: Int, val reason: String)

    private suspend fun findMatches(
        apiKey: String,
        rules: String,
        messages: List<Message>
    ): List<Match> {
        val numbered = messages.mapIndexed { i, m ->
            "$i. [${m.chatName}] ${m.senderName}: ${m.messageContent}"
        }.joinToString("\n")

        val system = "You triage group chat messages. You reply with JSON only, no prose."

        val prompt = """
            The user wants to be notified only about messages matching these interests:
            ---
            $rules
            ---

            Here are new messages from their WhatsApp groups, each with an index:
            ---
            $numbered
            ---

            Return a JSON array of the messages that genuinely match the user's
            interests. Each element: {"index": <number>, "reason": "<max 12 words,
            in the language of the message, saying why it matters>"}

            Be strict. Most messages will not match - return [] in that case. Do not
            match a message merely because it shares a topic; it must be something the
            user would want to be interrupted for.
        """.trimIndent()

        val reply = AiClient.complete(
            apiKey = apiKey,
            systemMessage = system,
            userMessage = prompt,
            maxTokens = 800,
            // Deterministic: the same message should not alert only sometimes.
            temperature = 0.0
        )

        val array = AiClient.extractJsonArray(reply)
        val matches = mutableListOf<Match>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val index = item.optInt("index", -1)
            if (index !in messages.indices) continue
            matches.add(Match(index, item.optString("reason", "").trim()))
        }
        return matches.take(MAX_ALERTS_PER_BATCH)
    }

    private fun chatIntent(context: Context, chatName: String): Intent {
        return Intent(context, ChatActivity::class.java).apply {
            putExtra("CHAT_NAME", chatName)
            putStringArrayListExtra("CHAT_VARIATIONS", arrayListOf(chatName))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    companion object {
        private const val TAG = "AlertWorker"
        private const val WORK_NAME = "smart_alert_check"

        /** Wait this long after a message before checking, to batch a burst. */
        private const val DEBOUNCE_SECONDS = 90L

        private const val MAX_MESSAGES_PER_BATCH = 60
        /** Cap so a bad rule set cannot produce a notification storm. */
        private const val MAX_ALERTS_PER_BATCH = 4

        /**
         * Queues a check, replacing any already pending. Called on every captured
         * message, which is what makes the debounce collapse bursts into one call.
         */
        fun schedule(context: Context) {
            if (!AlertSettings(context).isUsable()) return

            val request = OneTimeWorkRequestBuilder<AlertWorker>()
                .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
