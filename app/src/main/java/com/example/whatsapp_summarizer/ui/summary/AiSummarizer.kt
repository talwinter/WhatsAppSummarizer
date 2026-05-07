package com.example.whatsapp_summarizer.ui.summary

import android.content.Context
import android.util.Log
import com.example.whatsapp_summarizer.data.model.Message
import com.example.whatsapp_summarizer.util.LocalModelManager
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

    private val client by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun summarizeMessages(
        messages: List<Message>, 
        apiKey: String,
        inHebrew: Boolean = false,
        useLocalModel: Boolean = false,
        context: Context? = null
    ): String = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) {
            return@withContext if (inHebrew) "אין הודעות לסיכום." else "No messages to summarize."
        }

        val conversation = messages.joinToString("\n") { msg ->
            "[${msg.getFormattedTime()}] ${msg.senderName}: ${msg.messageContent}"
        }

        if (useLocalModel && context != null) {
            // Use local TFLite model
            val localManager = LocalModelManager(context)
            if (!localManager.isModelAvailable()) {
                return@withContext if (inHebrew) {
                    "מודל מקומי לא זמין. עבור להגדרות כדי לייבא מודל או השתמש ב-OpenAI."
                } else {
                    "Local model not available. Go to Settings to import a model or use OpenAI."
                }
            }
            
            val loadResult = localManager.loadModel()
            loadResult.fold(
                onSuccess = {
                    val result = localManager.summarize(conversation, inHebrew)
                    localManager.unloadModel()
                    return@withContext result
                },
                onFailure = { error ->
                    return@withContext if (inHebrew) {
                        "שגיאה בטעינת המודל המקומי: ${error.message}. נסה להשתמש ב-OpenAI."
                    } else {
                        "Failed to load local model: ${error.message}. Try using OpenAI."
                    }
                }
            )
        }

        // Use cloud API (OpenAI or Gemini)
        val prompt = if (inHebrew) {
            """
                אנא סכם את השיחה הבאה בוואטסאפ בעברית.
                ספק סיכום תמציתי של הנושאים המרכזיים שנדונו ושל החלטות או משימות חשובות.
                
                שיחה:
                $conversation
            """.trimIndent()
        } else {
            """
                Please summarize the following WhatsApp conversation. 
                Provide a concise summary of the main topics discussed and any important decisions or action items.
                
                Conversation:
                $conversation
            """.trimIndent()
        }

        if (apiKey.startsWith("sk-")) {
            summarizeWithOpenAI(prompt, apiKey, inHebrew)
        } else {
            summarizeWithGemini(prompt, apiKey, inHebrew)
        }
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
            put("max_tokens", 500)
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
