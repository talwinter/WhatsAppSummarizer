package com.example.whatsapp_summarizer.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val chatName: String,
    val senderName: String,
    val messageContent: String,
    val timestamp: Long,
    val isGroup: Boolean = false
) {
    fun getFormattedTime(): String {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun getFormattedDate(): String {
        val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
