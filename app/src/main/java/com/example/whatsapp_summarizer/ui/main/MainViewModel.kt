package com.example.whatsapp_summarizer.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import com.example.whatsapp_summarizer.data.model.ChatCount
import com.example.whatsapp_summarizer.data.model.Message
import com.example.whatsapp_summarizer.data.repository.MessageRepository
import com.example.whatsapp_summarizer.util.ChatNameNormalizer
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as com.example.whatsapp_summarizer.WhatsAppSummarizerApp).repository
    
    // Raw data from database - only group chats
    private val rawChatNames: LiveData<List<String>> = repository.groupChatNames
    val allMessages: LiveData<List<Message>> = repository.allMessages

    // Mapping: normalized name -> list of raw names (for querying messages)
    private val _nameMapping = MutableLiveData<Map<String, List<String>>>()
    val nameMapping: LiveData<Map<String, List<String>>> = _nameMapping

    private val _chatCounts = MutableLiveData<List<ChatCount>>()
    val chatCounts: LiveData<List<ChatCount>> = _chatCounts

    // Merged chat names (deduplicated by normalization) - only group chats
    val allChatNames: LiveData<List<String>> = rawChatNames.map { names ->
        // Group by normalized name, use normalized name as display name
        names.groupBy { ChatNameNormalizer.normalize(it) }
            .keys
            .sorted()
    }

    init {
        // Observe raw names and build mapping + reload counts
        rawChatNames.observeForever { names ->
            val mapping = mutableMapOf<String, MutableList<String>>()
            names.forEach { name ->
                val normalized = ChatNameNormalizer.normalize(name)
                mapping.getOrPut(normalized) { mutableListOf() }.add(name)
            }
            _nameMapping.postValue(mapping)
            // Auto-refresh counts whenever chat names change
            refreshCounts()
        }
    }

    private fun refreshCounts() {
        viewModelScope.launch {
            val rawCounts = repository.getGroupChatMessageCounts()
            // Merge counts by normalized name
            val merged = mutableMapOf<String, Int>()
            rawCounts.forEach { chatCount ->
                val normalized = ChatNameNormalizer.normalize(chatCount.chatName)
                merged[normalized] = (merged[normalized] ?: 0) + chatCount.count
            }
            _chatCounts.postValue(merged.map { (name, count) -> ChatCount(name, count) }
                .sortedByDescending { it.count })
        }
    }

    fun deleteChat(chatName: String) {
        viewModelScope.launch {
            // Delete ALL variations of this chat name
            val mapping = _nameMapping.value ?: emptyMap()
            val normalized = ChatNameNormalizer.normalize(chatName)
            val variations = mapping[normalized] ?: listOf(chatName)
            variations.forEach { repository.deleteChat(it) }
        }
    }

    fun cleanupOldMessages() {
        viewModelScope.launch {
            repository.cleanupOldMessages(30)
        }
    }

    fun removeDuplicateMessages(callback: (Int) -> Unit) {
        viewModelScope.launch {
            val removed = repository.removeDuplicateMessages()
            callback(removed)
        }
    }

    fun mergeSimilarChats(callback: (Int) -> Unit) {
        viewModelScope.launch {
            val merged = repository.mergeSimilarChats()
            callback(merged)
        }
    }

    fun loadChatCounts() {
        refreshCounts()
    }
}
