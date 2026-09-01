package com.example.whatsapp_summarizer.ui.summary

import android.util.Log
import com.example.whatsapp_summarizer.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object AiSummarizer {

    private const val OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
    private const val GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent"

    // Output budget for the summary itself. A day of busy group chat needs far
    // more than the 500 tokens this used to allow.
    private const val MAX_OUTPUT_TOKENS = 4000

    // Ceiling on the conversation we send up. gpt-4.1-mini has a very large
    // context window, so this is only a guard against a pathological payload -
    // it is not meant to trim ordinary usage. Roughly 100k tokens of chat.
    private const val MAX_INPUT_CHARS = 400_000

    private val client by lazy {
        // Body-level logging is deliberately off: it writes the entire
        // conversation and the Authorization header into logcat.
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            // Large payloads take time to upload and much longer to summarize.
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .build()
    }

    suspend fun summarizeMessages(
        messages: List<Message>,
        apiKey: String,
        inHebrew: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) {
            return@withContext if (inHebrew) "אין הודעות לסיכום." else "No messages to summarize."
        }

        val (conversation, droppedCount) = buildConversation(messages)

        // Use cloud API (OpenAI or Gemini)
        val truncationNote = when {
            droppedCount <= 0 -> ""
            inHebrew -> "(שים לב: $droppedCount ההודעות הראשונות הושמטו בגלל אורך השיחה.)" + "\n\n"
            else -> "(Note: the oldest $droppedCount messages were omitted because the conversation was very long.)" + "\n\n"
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

        if (apiKey.startsWith("sk-")) {
            summarizeWithOpenAI(prompt, apiKey, inHebrew)
        } else {
            summarizeWithGemini(prompt, apiKey, inHebrew)
        }
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

    private fun summarizeWithOpenAI(prompt: String, apiKey: String, inHebrew: Boolean): String {
        val systemMsg = if (inHebrew) {
            "אתה עוזר מועיל שמסכם שיחות בצורה תמציתית בעברית."
        } else {
            "You are a helpful assistant that summarizes conversations concisely."
        }

        val json = JSONObject().apply {
            put("model", "gpt-4.1-mini")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemMsg)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("temperature", 0.7)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(OPENAI_API_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful) {
                Log.e("AiSummarizer", "API Error: ${response.code}, Body: $responseBody")
                throw IOException("OpenAI API error ${response.code}: ${responseBody ?: "Unknown error"}")
            }

            responseBody ?: throw IOException("Empty response from OpenAI")
            
            try {
                val responseJson = JSONObject(responseBody)
                val choices = responseJson.getJSONArray("choices")
                return choices.getJSONObject(0).getJSONObject("message").getString("content")
            } catch (e: Exception) {
                Log.e("AiSummarizer", "Failed to parse response: $responseBody", e)
                throw IOException("Failed to parse OpenAI response: ${e.message}")
            }
        }
    }

    private fun summarizeWithGemini(prompt: String, apiKey: String, inHebrew: Boolean): String {
        val fullPrompt = if (inHebrew) {
            "סכם את השיחה הבאה בעברית:\n\n$prompt"
        } else {
            prompt
        }

        val json = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", fullPrompt)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", MAX_OUTPUT_TOKENS)
            })
        }

        val url = "$GEMINI_API_URL?key=$apiKey"
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
            
            if (!response.isSuccessful) {
                Log.e("AiSummarizer", "API Error: ${response.code}, Body: $responseBody")
                throw IOException("Gemini API error ${response.code}: ${responseBody ?: "Unknown error"}")
            }

            responseBody ?: throw IOException("Empty response from Gemini")
            
            try {
                val responseJson = JSONObject(responseBody)
                val candidates = responseJson.getJSONArray("candidates")
                val content = candidates.getJSONObject(0).getJSONObject("content")
                val parts = content.getJSONArray("parts")
                return parts.getJSONObject(0).getString("text")
            } catch (e: Exception) {
                Log.e("AiSummarizer", "Failed to parse response: $responseBody", e)
                throw IOException("Failed to parse Gemini response: ${e.message}")
            }
        }
    }
}
