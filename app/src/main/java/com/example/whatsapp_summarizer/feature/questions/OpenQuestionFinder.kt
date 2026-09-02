package com.example.whatsapp_summarizer.feature.questions

import com.example.whatsapp_summarizer.ai.AiClient
import com.example.whatsapp_summarizer.data.model.Message

/**
 * Finds questions in group chats that nobody answered.
 *
 * Messaging apps show you a chronological stream and leave you to notice that a
 * question scrolled past unanswered - which in a busy group is exactly what happens.
 * Because the app holds the whole recent history per group, it can look at a question
 * together with everything said after it and judge whether a reply ever came.
 */
object OpenQuestionFinder {

    /** Enough context to tell an ignored question from an answered one. */
    private const val LOOKBACK_DAYS = 3
    private const val MAX_MESSAGES = 400
    private const val MAX_OUTPUT_TOKENS = 1500

    data class OpenQuestion(
        /** Row id of the asking message, so tapping it can jump to that exact line. */
        val messageId: Long,
        val chatName: String,
        val senderName: String,
        val question: String,
        val timestamp: Long
    )

    suspend fun find(
        messages: List<Message>,
        apiKey: String,
        inHebrew: Boolean
    ): List<OpenQuestion> {
        if (messages.isEmpty()) return emptyList()

        // Group by chat: whether a question was answered only makes sense within
        // its own conversation.
        val byChat = messages.groupBy { it.chatName }
        val results = mutableListOf<OpenQuestion>()

        for ((chatName, chatMessages) in byChat) {
            val ordered = chatMessages.sortedBy { it.timestamp }.takeLast(MAX_MESSAGES)
            // A lone message cannot have been answered or ignored meaningfully.
            if (ordered.size < 2) continue
            results += findInChat(chatName, ordered, apiKey, inHebrew)
        }

        return results.sortedByDescending { it.timestamp }
    }

    private suspend fun findInChat(
        chatName: String,
        ordered: List<Message>,
        apiKey: String,
        inHebrew: Boolean
    ): List<OpenQuestion> {
        val transcript = ordered.mapIndexed { i, m ->
            "$i. [${m.getFormattedDate()} ${m.getFormattedTime()}] ${m.senderName}: ${m.messageContent}"
        }.joinToString("\n")

        val system = "You analyse group chat transcripts. You reply with JSON only, no prose."

        val language = if (inHebrew) "Hebrew" else "the language of the question"

        val prompt = """
            Below is a WhatsApp group transcript in chronological order, one indexed
            message per line.

            Find questions or requests that were never answered. A question counts as
            ANSWERED if any later message addresses it, even loosely or partially.
            A question counts as OPEN only if nothing after it responds to it.

            Return a JSON array, newest first. Each element:
            {"index": <index of the question>, "summary": "<the question in one short
            line, in $language>"}

            Ignore rhetorical questions, greetings and small talk. If every question
            got some kind of reply, return [].

            Transcript:
            ---
            $transcript
            ---
        """.trimIndent()

        val reply = AiClient.complete(
            apiKey = apiKey,
            systemMessage = system,
            userMessage = prompt,
            maxTokens = MAX_OUTPUT_TOKENS,
            // Deterministic: the same history should yield the same list.
            temperature = 0.0
        )

        val array = AiClient.extractJsonArray(reply)
        val found = mutableListOf<OpenQuestion>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val index = item.optInt("index", -1)
            val source = ordered.getOrNull(index) ?: continue
            val summary = item.optString("summary", "").trim()
            found.add(
                OpenQuestion(
                    messageId = source.id,
                    chatName = chatName,
                    senderName = source.senderName,
                    // Fall back to the raw message if the model gave us nothing usable.
                    question = summary.ifBlank { source.messageContent },
                    timestamp = source.timestamp
                )
            )
        }
        return found
    }

    /** Start of the lookback window, as an epoch milli. */
    fun lookbackStart(): Long =
        System.currentTimeMillis() - LOOKBACK_DAYS * 24L * 60L * 60L * 1000L
}
