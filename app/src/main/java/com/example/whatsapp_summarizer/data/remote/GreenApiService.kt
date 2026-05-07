package com.example.whatsapp_summarizer.data.remote

import com.example.whatsapp_summarizer.data.remote.model.GreenApiContact
import com.example.whatsapp_summarizer.data.remote.model.GreenApiMessage
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface GreenApiService {

    @GET("waInstance{idInstance}/getContacts/{apiTokenInstance}")
    suspend fun getContacts(
        @Path("idInstance") idInstance: String,
        @Path("apiTokenInstance") apiTokenInstance: String,
        @Query("group") group: Boolean? = null
    ): Response<List<GreenApiContact>>

    @POST("waInstance{idInstance}/getChatHistory/{apiTokenInstance}")
    suspend fun getChatHistory(
        @Path("idInstance") idInstance: String,
        @Path("apiTokenInstance") apiTokenInstance: String,
        @Body request: ChatHistoryRequest
    ): Response<List<GreenApiMessage>>

    data class ChatHistoryRequest(
        val chatId: String,
        val count: Int = 100
    )
}
