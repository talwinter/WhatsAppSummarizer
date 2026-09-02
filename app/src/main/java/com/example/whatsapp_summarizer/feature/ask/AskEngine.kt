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

    /** Enough to show where an answer came from without a wall of cards. */
    private const val MAX_CITATIONS = 6

    /** Words too common to help rank anything. */
    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "what",
        "who", "when", "where", "why", "how", "did", "do", "does", "is", "are", "was",
        "were", "about", "that", "this", "it", "we", "they", "i", "my", "our",
        "מה", "מי", "איפה", "למה", "איך", "מתי", "של", "את", "עם", "על", "אני", "אנחנו",
        "הם", "זה", "זאת", "כל", "יש", "לא", "כן"
    )

    /**
     * One message the answer leaned on. Carries the whole [Message], so its database
     * id can take the user straight to that line in the transcript.
     */
    data class Citation(val message: Message, val note: String)

    data class Answer(
        val text: String,
        val citations: List<Citation>,
        val sourcesConsidered: Int
    )

    suspend fun ask(
        question: String,
        messages: List<Message>,
        apiKey: String,
        inHebrew: Boolean
    ): Answer {
        if (messages.isEmpty()) {
            return Answer(
                if (inHebrew) "אין הודעות שמורות לענות עליהן." else "No captured messages to answer from.",
                emptyList(),
                0
            )
        }

        val ranked = rank(question, messages)
        // `chosen` is the exact list the model sees, in the same order, so the indices
        // it returns map straight back to real database rows.
        val chosen = selectWithinBudget(ranked)
        val context = chosen
            .mapIndexed { i, m -> "$i. ${render(m)}" }
            .joinToString("\n")

        val system = if (inHebrew) {
            "אתה עונה על שאלות על סמך הודעות וואטסאפ שמורות. ענה בעברית. החזר JSON בלבד."
        } else {
            "You answer questions using saved WhatsApp messages only. Reply with JSON only."
        }

        val prompt = if (inHebrew) {
            """
                ענה על השאלה הבאה על סמך ההודעות הממוספרות בלבד.
                אל תמציא מידע. אם התשובה לא נמצאת בהודעות, אמור זאת במפורש.

                החזר JSON בפורמט:
                {"answer": "<התשובה, בעברית>",
                 "sources": [{"index": <מספר ההודעה>, "note": "<עד 10 מילים: מה ההודעה תרמה>"}]}

                כלול ב-sources רק הודעות שהתשובה באמת מסתמכת עליהן, לפי סדר החשיבות.
                אם התשובה לא נמצאת בהודעות, החזר sources ריק.

                שאלה: $question

                הודעות:
                ---
                $context
                ---
            """.trimIndent()
        } else {
            """
                Answer the question below using only the numbered messages.
                Do not invent anything. If the answer is not in the messages, say so plainly.

                Return JSON of the form:
                {"answer": "<the answer>",
                 "sources": [{"index": <message number>, "note": "<max 10 words: what this
                 message contributed>"}]}

                Include in "sources" only messages the answer genuinely relies on, most
                important first. If the messages do not contain the answer, return an
                empty sources list.

                Question: $question

                Messages:
                ---
                $context
                ---
            """.trimIndent()
        }

        val reply = AiClient.complete(
            apiKey = apiKey,
            systemMessage = system,
            userMessage = prompt,
            maxTokens = MAX_OUTPUT_TOKENS,
            // Low but not zero: factual recall, with room for readable phrasing.
            temperature = 0.2
        )

        val parsed = AiClient.extractJsonObject(reply)
        // No parseable JSON: show the reply as prose rather than failing. The answer is
        // still useful even when the citation structure did not come through.
        val answerText = parsed?.optString("answer")?.takeIf { it.isNotBlank() } ?: reply

        val citations = mutableListOf<Citation>()
        val sources = parsed?.optJSONArray("sources")
        if (sources != null) {
            val seen = mutableSetOf<Long>()
            for (i in 0 until sources.length()) {
                val item = sources.optJSONObject(i) ?: continue
                val message = chosen.getOrNull(item.optInt("index", -1)) ?: continue
                // A model can cite the same line twice; show it once.
                if (!seen.add(message.id)) continue
                citations.add(Citation(message, item.optString("note", "").trim()))
                if (citations.size >= MAX_CITATIONS) break
            }
        }

        return Answer(answerText.trim(), citations, chosen.size)
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
     * Fills the context budget with the highest-ranked messages, then returns them
     * chronologically - the model reasons better about a conversation in order.
     */
    private fun selectWithinBudget(ranked: List<Message>): List<Message> {
        val chosen = mutableListOf<Message>()
        var used = 0
        for (message in ranked) {
            val cost = render(message).length + 8   // + room for the index prefix
            if (used + cost > MAX_CONTEXT_CHARS) break
            chosen.add(message)
            used += cost
        }
        return chosen.sortedBy { it.timestamp }
    }

    private fun render(message: Message): String =
        "[${message.chatName} · ${message.senderName} · " +
            "${message.getFormattedDate()} ${message.getFormattedTime()}] ${message.messageContent}"

    fun candidatePoolSize(): Int = CANDIDATE_POOL
}
