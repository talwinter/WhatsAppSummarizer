package com.example.whatsapp_summarizer.data.remote

import com.example.whatsapp_summarizer.data.remote.model.GreenApiContact
import com.example.whatsapp_summarizer.data.remote.model.GreenApiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class GreenApiRepository {

    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.green-api.com/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val service = retrofit.create(GreenApiService::class.java)

    suspend fun getContacts(idInstance: String, apiTokenInstance: String, onlyGroups: Boolean = false): Result<List<GreenApiContact>> =
        withContext(Dispatchers.IO) {
            try {
                val response = service.getContacts(idInstance, apiTokenInstance, onlyGroups)
                if (response.isSuccessful) {
                    val contacts = response.body()?.filter { it.id.isNotBlank() } ?: emptyList()
                    Result.success(contacts)
                } else {
                    Result.failure(Exception("API Error: ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun getChatHistory(
        idInstance: String,
        apiTokenInstance: String,
        chatId: String,
        count: Int = 100
    ): Result<List<GreenApiMessage>> = withContext(Dispatchers.IO) {
        try {
            val response = service.getChatHistory(
                idInstance,
                apiTokenInstance,
                GreenApiService.ChatHistoryRequest(chatId, count)
            )
            if (response.isSuccessful) {
                val messages = response.body()?.filter { 
                    (it.isDeleted != true) && it.isTextMessage() 
                } ?: emptyList()
                Result.success(messages)
            } else {
                Result.failure(Exception("API Error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
