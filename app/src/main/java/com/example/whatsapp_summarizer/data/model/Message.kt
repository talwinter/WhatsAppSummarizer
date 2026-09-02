package com.example.whatsapp_summarizer.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Date

/**
 * A captured message.
 *
 * The unique index is what makes duplicates impossible. WhatsApp re-posts every
 * active conversation notification whenever any new message arrives anywhere, so
 * the same message is delivered to the listener over and over - one message was
 * seen 24 times across three hours. Since [timestamp] is WhatsApp's own send time
 * rather than capture time, a re-post produces an identical row and the database
 * rejects it.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(
            value = ["chatName", "senderName", "messageContent", "timestamp"],
            unique = true
        )
    ]
)
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
