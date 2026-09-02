package com.example.whatsapp_summarizer.ui.summary

import android.util.Log
import com.example.whatsapp_summarizer.ai.AiClient
import com.example.whatsapp_summarizer.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds the summarization prompt. The HTTP call lives in [AiClient].
 */
object AiSummarizer {

    // Output budget for the summary itself. A day of busy group chat needs far
    // more than the 500 tokens this used to allow.
    private const val MAX_OUTPUT_TOKENS = 4000

    // Ceiling on the conversation we send up. gpt-4.1-mini has a very large
    // context window, so this is only a guard against a pathological payload -
    // it is not meant to trim ordinary usage. Roughly 100k tokens of chat.
    private const val MAX_INPUT_CHARS = 400_000

    suspend fun summarizeMessages(
        messages: List<Message>,
        apiKey: String,
        inHebrew: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) {
            return@withContext if (inHebrew) "אין הודעות לסיכום." else "No messages to summarize."
        }

        val (conversation, droppedCount) = buildConversation(messages)

        val truncationNote = when {
            droppedCount <= 0 -> ""
            inHebrew -> "(שים לב: $droppedCount ההודעות הראשונות הושמטו בגלל אורך השיחה.)\n\n"
            else -> "(Note: the oldest $droppedCount messages were omitted because " +
                "the conversation was very long.)\n\n"
        }

        val systemMessage = if (inHebrew) {
            "אתה עוזר מועיל שמסכם שיחות בצורה תמציתית בעברית."
        } else {
            "You are a helpful assistant that summarizes conversations concisely."
        }

        val prompt = if (inHebrew) {
            """
                אנא סכם את השיחה הבאה בוואטסאפ בעברית.
                סכם את כל השיחה - אל תדלג על נושאים. עבור כל נושא שנדון, ציין מי אמר מה כשזה חשוב.
                בסוף הסיכום הוסף רשימת החלטות ומשימות פתוחות, אם יש כאלה.

                $truncationNote שיחה:
                $conversation
            """.trimIndent()
        } else {
            """
                Please summarize the following WhatsApp conversation.
                Cover the whole conversation - do not skip topics. For each topic discussed,
                attribute points to the people who made them where that matters.
                End with a list of decisions made and any open action items.

                $truncationNote Conversation:
                $conversation
            """.trimIndent()
        }

        AiClient.complete(
            apiKey = apiKey,
            systemMessage = systemMessage,
            userMessage = prompt,
            maxTokens = MAX_OUTPUT_TOKENS
        )
    }

    /**
     * Renders the messages into the transcript we send to the model.
     *
     * The whole conversation goes up unless it would exceed [MAX_INPUT_CHARS]; if it
     * would, the OLDEST messages are dropped so the most recent context survives.
     * Returns the transcript together with the number of messages left out.
     */
    private fun buildConversation(messages: List<Message>): Pair<String, Int> {
        val lines = messages.map { msg ->
            "[${msg.getFormattedTime()}] ${msg.senderName}: ${msg.messageContent}"
        }

        val total = lines.sumOf { it.length + 1 }
        if (total <= MAX_INPUT_CHARS) {
            return lines.joinToString("\n") to 0
        }

        // Walk backwards from the newest message until the budget is spent.
        var used = 0
        var firstKept = lines.size
        for (i in lines.indices.reversed()) {
            val cost = lines[i].length + 1
            if (used + cost > MAX_INPUT_CHARS) break
            used += cost
            firstKept = i
        }

        val kept = lines.subList(firstKept, lines.size)
        Log.w(
            "AiSummarizer",
            "Conversation trimmed: sent ${kept.size} of ${lines.size} messages ($used chars)"
        )
        return kept.joinToString("\n") to firstKept
    }
}
