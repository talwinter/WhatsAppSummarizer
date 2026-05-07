package com.example.whatsapp_summarizer.ui.group

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.whatsapp_summarizer.data.model.Message
import com.example.whatsapp_summarizer.data.repository.MessageRepository
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as com.example.whatsapp_summarizer.WhatsAppSummarizerApp).repository
    
    private val _messages = MutableLiveData<List<Message>>()
    val messages: LiveData<List<Message>> = _messages

    fun loadMessages(chatName: String) {
        // Use the LiveData from repository instead
        _messages.value = emptyList()
    }

    fun getMessagesByChat(chatName: String): LiveData<List<Message>> {
        return repository.getMessagesByChat(chatName)
    }

    fun getMessagesByVariations(variations: List<String>): LiveData<List<Message>> {
        return if (variations.size == 1) {
            repository.getMessagesByChat(variations.first())
        } else {
            repository.getMessagesByChatNames(variations)
        }
    }

    fun getTodayMessages(chatName: String, callback: (List<Message>) -> Unit) {
        viewModelScope.launch {
            val todayMessages = repository.getTodayMessages(chatName)
            callback(todayMessages)
        }
    }
}
