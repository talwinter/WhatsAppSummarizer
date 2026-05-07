package com.example.whatsapp_summarizer

import android.app.Application
import com.example.whatsapp_summarizer.data.database.AppDatabase
import com.example.whatsapp_summarizer.data.repository.MessageRepository

class WhatsAppSummarizerApp : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { MessageRepository(database.messageDao()) }
}
