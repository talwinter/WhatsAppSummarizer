package com.example.whatsapp_summarizer.feature.digest

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.whatsapp_summarizer.R
import com.example.whatsapp_summarizer.WhatsAppSummarizerApp
import com.example.whatsapp_summarizer.ai.AiClient
import com.example.whatsapp_summarizer.data.model.Message
import com.example.whatsapp_summarizer.util.Notifier
import com.example.whatsapp_summarizer.util.SecureStorage
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Posts one quiet summary of the day across every group, at a time the user picks.
 *
 * The on-demand summary answers "what happened in this group"; this answers "what
 * happened everywhere" without being asked, which is the version you actually get
 * value from when you have nine groups.
 */
class DigestWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val settings = DigestSettings(context)
        if (!settings.enabled) return Result.success()

        val apiKey = SecureStorage(context).getApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Digest enabled but no API key configured")
            return Result.success()
        }
        if (!Notifier.canPost(context)) {
            Log.w(TAG, "Digest enabled but notifications are not permitted")
            return Result.success()
        }

        val repository = (context as WhatsAppSummarizerApp).repository
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val inHebrew = prefs.getBoolean("hebrew_language", false)

        val messages = repository.getGroupMessagesInRange(startOfToday(), System.currentTimeMillis())
        if (messages.isEmpty()) {
            Log.i(TAG, "No messages today, skipping digest")
            return Result.success()
        }

        return try {
            val digest = summarize(apiKey, messages, inHebrew)
            settings.lastDigest = digest
            settings.lastDigestAt = System.currentTimeMillis()

            Notifier.postDigest(
                context = context,
                title = context.getString(
                    R.string.digest_notification_title,
                    messages.map { it.chatName }.distinct().size
                ),
                body = digest,
                intent = Intent(context, DigestActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Digest failed", e)
            // Worth one retry: unlike an alert, a digest is not time-critical, so
            // arriving late is much better than not arriving.
            Result.retry()
        }
    }

    private suspend fun summarize(
        apiKey: String,
        messages: List<Message>,
        inHebrew: Boolean
    ): String {
        // Grouped by chat so the model summarizes per group rather than blending
        // nine unrelated conversations into one narrative.
        val byChat = messages.groupBy { it.chatName }
        val transcript = byChat.entries.joinToString("\n\n") { (chat, chatMessages) ->
            val lines = chatMessages
                .sortedBy { it.timestamp }
                .joinToString("\n") { "  [${it.getFormattedTime()}] ${it.senderName}: ${it.messageContent}" }
            "### $chat\n$lines"
        }

        val system = if (inHebrew) {
            "אתה מסכם יום של הודעות בקבוצות וואטסאפ בעברית, בקצרה ולעניין."
        } else {
            "You summarize a day of WhatsApp group messages briefly and concretely."
        }

        val prompt = if (inHebrew) {
            """
                סכם את היום בקבוצות הבאות.
                שורה או שתיים לכל קבוצה, בפורמט "שם הקבוצה: מה קרה".
                דלג על קבוצות שבהן לא קרה שום דבר משמעותי.
                אם יש משהו שדורש תגובה או החלטה, ציין זאת בסוף תחת "דורש תשומת לב".

                $transcript
            """.trimIndent()
        } else {
            """
                Summarize today across these groups.
                One or two lines per group, formatted "Group name: what happened".
                Skip groups where nothing meaningful happened.
                If anything needs a reply or a decision, list it at the end under
                "Needs attention".

                $transcript
            """.trimIndent()
        }

        return AiClient.complete(
            apiKey = apiKey,
            systemMessage = system,
            userMessage = prompt,
            maxTokens = MAX_OUTPUT_TOKENS,
            temperature = 0.4
        ).trim()
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    companion object {
        private const val TAG = "DigestWorker"
        private const val WORK_NAME = "daily_digest"
        private const val MAX_OUTPUT_TOKENS = 1500

        /**
         * (Re)applies the daily schedule. Safe to call on every launch: KEEP means an
         * existing schedule is left alone, and the delay is recomputed only when the
         * user actually changes the hour (see [rescheduleNow]).
         */
        fun reschedule(context: Context) {
            val settings = DigestSettings(context)
            if (!settings.enabled) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                return
            }
            enqueue(context, settings, ExistingPeriodicWorkPolicy.KEEP)
        }

        /** Used when the user changes the time, so the new hour takes effect today. */
        fun rescheduleNow(context: Context) {
            val settings = DigestSettings(context)
            if (!settings.enabled) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                return
            }
            enqueue(context, settings, ExistingPeriodicWorkPolicy.UPDATE)
        }

        private fun enqueue(
            context: Context,
            settings: DigestSettings,
            policy: ExistingPeriodicWorkPolicy
        ) {
            val request = PeriodicWorkRequestBuilder<DigestWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(millisUntilNextRun(settings.hour, settings.minute), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, policy, request)
        }

        /** Delay until the next occurrence of the chosen wall-clock time. */
        private fun millisUntilNextRun(hour: Int, minute: Int): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (target.timeInMillis <= now.timeInMillis) {
                target.add(Calendar.DAY_OF_MONTH, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }
    }
}
