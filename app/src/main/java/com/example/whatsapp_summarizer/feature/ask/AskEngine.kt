package com.example.whatsapp_summarizer.feature.ask

import com.example.whatsapp_summarizer.ai.AiClient
import com.example.whatsapp_summarizer.data.model.Message

/**
 * Answers a question using everything captured across every group.
 *
 * WhatsApp's own search is literal and per-chat: you must already know the word that
 * was used, and which conversation to look in. This is semantic and cross-group -
 * "what did they decide about the trip?" works without knowing either.
 *
 * Because it runs over history already on the device, it is useful from day one
 * rather than only for messages captured after the feature was switched on.
 */
object AskEngine {

    /** Pool of recent messages considered before scoring. */
    private const val CANDIDATE_POOL = 4000

    /** Context budget sent to the model - generous, since the corpus is small. */
    private const val MAX_CONTEXT_CHARS = 120_000

    private const val MAX_OUTPUT_TOKENS = 1200

    /** Words too common to help rank anything. */
    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "what",
        "who", "when", "where", "why", "how", "did", "do", "does", "is", "are", "was",
        "were", "about", "that", "this", "it", "we", "they", "i", "my", "our",
        "מה", "מי", "איפה", "למה", "איך", "מתי", "של", "את", "עם", "על", "אני", "אנחנו",
        "הם", "זה", "זאת", "כל", "יש", "לא", "כן"
    )

    data class Answer(val text: String, val sourcesUsed: Int, val truncated: Boolean)

    suspend fun ask(
        question: String,
        messages: List<Message>,
        apiKey: String,
        inHebrew: Boolean
    ): Answer {
        if (messages.isEmpty()) {
            return Answer(
                if (inHebrew) "אין הודעות שמורות לענות עליהן." else "No captured messages to answer from.",
                0,
                false
            )
        }

        val ranked = rank(question, messages)
        val (context, used, truncated) = buildContext(ranked)

        val system = if (inHebrew) {
            "אתה עונה על שאלות על סמך הודעות וואטסאפ שמורות. ענה בעברית."
        } else {
            "You answer questions using saved WhatsApp messages only."
        }

        val prompt = if (inHebrew) {
            """
                ענה על השאלה הבאה על סמך ההודעות בלבד.
                אל תמציא מידע. אם התשובה לא נמצאת בהודעות, אמור זאת במפורש.
                אחרי התשובה, הוסף שורות מקור בפורמט: [קבוצה · שולח · תאריך]

                שאלה: $question

                הודעות:
                ---
                $context
                ---
            """.trimIndent()
        } else {
            """
                Answer the question below using only these messages.
                Do not invent anything. If the answer is not in the messages, say so plainly.
                After the answer, list the sources you used as lines of the form:
                [group · sender · date]

                Question: $question

                Messages:
                ---
                $context
                ---
            """.trimIndent()
        }

        val text = AiClient.complete(
            apiKey = apiKey,
            systemMessage = system,
            userMessage = prompt,
            maxTokens = MAX_OUTPUT_TOKENS,
            // Low but not zero: factual recall, with room for readable phrasing.
            temperature = 0.2
        )
        return Answer(text.trim(), used, truncated)
    }

    /**
     * Orders messages by keyword overlap with the question, then recency.
     *
     * A deliberately simple lexical score rather than embeddings: the corpus is a few
     * thousand short messages, the whole thing nearly fits in context anyway, and this
     * needs no extra model call, no index to maintain and no network round trip.
     */
    private fun rank(question: String, messages: List<Message>): List<Message> {
        val terms = tokenize(question)
        if (terms.isEmpty()) return messages.sortedByDescending { it.timestamp }

        return messages
            .map { message ->
                val haystack = "${message.chatName} ${message.senderName} ${message.messageContent}"
                    .lowercase()
                val hits = terms.count { haystack.contains(it) }
                message to hits
            }
            // Matches first, and within equal relevance the most recent wins.
            .sortedWith(
                compareByDescending<Pair<Message, Int>> { it.second }
                    .thenByDescending { it.first.timestamp }
            )
            .map { it.first }
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 && it !in STOP_WORDS }
            .distinct()

    /**
     * Fills the context budget with the highest-ranked messages, then presents them
     * chronologically - the model reasons better about a conversation in order.
     */
    private fun buildContext(ranked: List<Message>): Triple<String, Int, Boolean> {
        val chosen = mutableListOf<Message>()
        var used = 0
        for (message in ranked) {
            val line = render(message)
            if (used + line.length + 1 > MAX_CONTEXT_CHARS) break
            chosen.add(message)
            used += line.length + 1
        }
        val truncated = chosen.size < ranked.size
        val text = chosen
            .sortedBy { it.timestamp }
            .joinToString("\n") { render(it) }
        return Triple(text, chosen.size, truncated)
    }

    private fun render(message: Message): String =
        "[${message.chatName} · ${message.senderName} · " +
            "${message.getFormattedDate()} ${message.getFormattedTime()}] ${message.messageContent}"

    fun candidatePoolSize(): Int = CANDIDATE_POOL
}
