package com.example.whatsapp_summarizer.data.remote.model

data class GreenApiContact(
    val id: String,
    val name: String?,
    val contactName: String?,
    val type: String // "user" or "group"
) {
    fun getDisplayName(): String {
        return name?.takeIf { it.isNotBlank() } 
            ?: contactName?.takeIf { it.isNotBlank() } 
            ?: id.replace("@c.us", "").replace("@g.us", "")
    }
    
    fun isGroup(): Boolean = type == "group" || id.endsWith("@g.us")
}
