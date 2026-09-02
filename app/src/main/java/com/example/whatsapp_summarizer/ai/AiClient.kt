package com.example.whatsapp_summarizer.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One place that talks to the model.
 *
 * Summaries, smart alerts, open-question detection and cross-group answers all go
 * through [complete] so there is a single HTTP path, a single timeout policy and a
 * single set of error semantics to reason about.
 *
 * The provider is chosen from the key shape, matching what the app did before:
 * keys starting with "sk-" go to OpenAI, anything else to Gemini.
 */
object AiClient {

    private const val OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
    private const val GEMINI_API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent"

    const val OPENAI_MODEL = "gpt-4.1-mini"

    private val client by lazy {
        // Body-level logging is deliberately off: it would write every conversation
        // and the Authorization header into logcat.
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            // Large payloads take time to upload and much longer to answer.
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Sends one prompt and returns the model's text.
     *
     * @param temperature low values for the classification-style calls (alerts,
     *   question detection) where we want the same answer every time.
     */
    suspend fun complete(
        apiKey: String,
        systemMessage: String,
        userMessage: String,
        maxTokens: Int,
        temperature: Double = 0.7
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.startsWith("sk-")) {
            callOpenAi(apiKey, systemMessage, userMessage, maxTokens, temperature)
        } else {
            callGemini(apiKey, systemMessage, userMessage, maxTokens, temperature)
        }
    }

    private fun callOpenAi(
        apiKey: String,
        systemMessage: String,
        userMessage: String,
        maxTokens: Int,
        temperature: Double
    ): String {
        val json = JSONObject().apply {
            put("model", OPENAI_MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemMessage)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
            })
            put("max_tokens", maxTokens)
            put("temperature", temperature)
        }

        val request = Request.Builder()
            .url(OPENAI_API_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) {
                Log.e("AiClient", "OpenAI error ${response.code}")
                throw IOException("OpenAI API error ${response.code}: ${body ?: "Unknown error"}")
            }
            body ?: throw IOException("Empty response from OpenAI")
            try {
                return JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } catch (e: Exception) {
                Log.e("AiClient", "Failed to parse OpenAI response", e)
                throw IOException("Failed to parse OpenAI response: ${e.message}")
            }
        }
    }

    private fun callGemini(
        apiKey: String,
        systemMessage: String,
        userMessage: String,
        maxTokens: Int,
        temperature: Double
    ): String {
        // Gemini has no separate system role on this endpoint, so it is prepended.
        val json = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "$systemMessage\n\n$userMessage")
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", maxTokens)
                put("temperature", temperature)
            })
        }

        val request = Request.Builder()
            .url("$GEMINI_API_URL?key=$apiKey")
            .header("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) {
                Log.e("AiClient", "Gemini error ${response.code}")
                throw IOException("Gemini API error ${response.code}: ${body ?: "Unknown error"}")
            }
            body ?: throw IOException("Empty response from Gemini")
            try {
                return JSONObject(body)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            } catch (e: Exception) {
                Log.e("AiClient", "Failed to parse Gemini response", e)
                throw IOException("Failed to parse Gemini response: ${e.message}")
            }
        }
    }

    /**
     * Pulls a JSON array out of a model reply.
     *
     * Models wrap JSON in prose or fenced code blocks often enough that parsing the
     * whole reply is unreliable, so we take the outermost bracketed span. Returns an
     * empty array rather than throwing, because for alerts and question detection a
     * malformed reply should mean "nothing found", never a crash in a background job.
     */
    fun extractJsonArray(reply: String): JSONArray {
        val start = reply.indexOf('[')
        val end = reply.lastIndexOf(']')
        if (start < 0 || end <= start) {
            Log.w("AiClient", "No JSON array in model reply")
            return JSONArray()
        }
        return try {
            JSONArray(reply.substring(start, end + 1))
        } catch (e: Exception) {
            Log.w("AiClient", "Malformed JSON array in model reply", e)
            JSONArray()
        }
    }
}
