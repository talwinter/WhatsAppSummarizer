package com.example.whatsapp_summarizer.data.remote.model

data class GreenApiMessage(
    val type: String, // "incoming" or "outgoing"
    val idMessage: String,
    val timestamp: Long,
    val typeMessage: String, // "textMessage", "extendedTextMessage", etc.
    val chatId: String?,
    val senderId: String?,
    val senderName: String?,
    val senderContactName: String?,
    val textMessage: String?,
    val extendedTextMessage: ExtendedTextMessage?,
    val statusMessage: String?,
    val isForwarded: Boolean?,
    val deletedMessageId: String?,
    val isDeleted: Boolean?
) {
    fun getDisplayText(): String {
        return textMessage?.takeIf { it.isNotBlank() }
            ?: extendedTextMessage?.text?.takeIf { it.isNotBlank() }
            ?: "[Media/Message]"
    }
    
    fun getSenderDisplayName(): String {
        return senderName?.takeIf { it.isNotBlank() }
            ?: senderContactName?.takeIf { it.isNotBlank() }
            ?: "You"
    }
    
    fun isTextMessage(): Boolean {
        return typeMessage == "textMessage" || typeMessage == "extendedTextMessage"
    }
}

data class ExtendedTextMessage(
    val text: String?,
    val description: String?,
    val title: String?
)
