package com.example.whatsapp_summarizer

import android.app.Application
import com.example.whatsapp_summarizer.data.database.AppDatabase
import com.example.whatsapp_summarizer.data.repository.MessageRepository
import com.example.whatsapp_summarizer.feature.digest.DigestWorker
import com.example.whatsapp_summarizer.util.Notifier

class WhatsAppSummarizerApp : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { MessageRepository(database.messageDao()) }

    override fun onCreate() {
        super.onCreate()
        // Channels must exist before anything tries to post, including a background
        // worker that may run before any activity has been opened.
        Notifier.ensureChannels(this)
        // Re-applies the daily digest schedule. KEEP means an existing schedule is
        // left alone, so this is safe on every launch.
        DigestWorker.reschedule(this)
    }
}
