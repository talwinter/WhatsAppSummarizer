package com.example.whatsapp_summarizer.data.repository

import androidx.lifecycle.LiveData
import com.example.whatsapp_summarizer.data.dao.MessageDao
import com.example.whatsapp_summarizer.data.model.ChatCount
import com.example.whatsapp_summarizer.data.model.Message
import com.example.whatsapp_summarizer.util.ChatNameNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

class MessageRepository(private val messageDao: MessageDao) {

    val allMessages: LiveData<List<Message>> = messageDao.getAllMessages()
    val allChatNames: LiveData<List<String>> = messageDao.getAllChatNames()
    val groupChatNames: LiveData<List<String>> = messageDao.getGroupChatNames()

    suspend fun insertMessage(message: Message): Long = withContext(Dispatchers.IO) {
        messageDao.insertMessage(message)
    }

    fun getMessagesByChat(chatName: String): LiveData<List<Message>> {
        return messageDao.getMessagesByChat(chatName)
    }

    fun getMessagesByChatNames(chatNames: List<String>): LiveData<List<Message>> {
        return messageDao.getMessagesByChatNames(chatNames)
    }

    suspend fun getTodayMessages(chatName: String): List<Message> = withContext(Dispatchers.IO) {
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis

        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val endOfDay = calendar.timeInMillis

        messageDao.getMessagesForDate(chatName, startOfDay, endOfDay)
    }

    suspend fun deleteChat(chatName: String) = withContext(Dispatchers.IO) {
        messageDao.deleteChat(chatName)
    }

    suspend fun getMessagesForTimeRange(chatName: String, startTime: Long, endTime: Long): List<Message> = withContext(Dispatchers.IO) {
        messageDao.getMessagesForTimeRange(chatName, startTime, endTime)
    }

    suspend fun cleanupOldMessages(daysToKeep: Int = 30) = withContext(Dispatchers.IO) {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_MONTH, -daysToKeep)
        messageDao.deleteMessagesOlderThan(calendar.timeInMillis)
    }

    suspend fun removeDuplicateMessages(): Int = withContext(Dispatchers.IO) {
        messageDao.removeDuplicateMessages()
    }

    suspend fun getChatMessageCounts(): List<ChatCount> = withContext(Dispatchers.IO) {
        val rawCounts = messageDao.getChatMessageCounts()
        val merged = mutableMapOf<String, Int>()
        
        rawCounts.forEach { chatCount ->
            val normalized = ChatNameNormalizer.normalize(chatCount.chatName)
            merged[normalized] = (merged[normalized] ?: 0) + chatCount.count
        }
        
        merged.map { (name, count) -> ChatCount(name, count) }
            .sortedByDescending { it.count }
    }

    suspend fun getGroupChatMessageCounts(): List<ChatCount> = withContext(Dispatchers.IO) {
        val rawCounts = messageDao.getGroupChatMessageCounts()
        val merged = mutableMapOf<String, Int>()
        
        rawCounts.forEach { chatCount ->
            val normalized = ChatNameNormalizer.normalize(chatCount.chatName)
            merged[normalized] = (merged[normalized] ?: 0) + chatCount.count
        }
        
        merged.map { (name, count) -> ChatCount(name, count) }
            .sortedByDescending { it.count }
    }

    suspend fun deleteAllMessages(): Int = withContext(Dispatchers.IO) {
        messageDao.deleteAllMessages()
    }

    suspend fun mergeSimilarChats(): Int = withContext(Dispatchers.IO) {
        val chatNames = messageDao.getAllChatNamesRaw()
        var mergedCount = 0

        // Group chats by normalized name
        val normalizedMap = mutableMapOf<String, MutableList<String>>()
        chatNames.forEach { name ->
            val normalized = ChatNameNormalizer.normalize(name)
            normalizedMap.getOrPut(normalized) { mutableListOf() }.add(name)
        }

        // For each group with multiple variants, merge into the first one
        normalizedMap.values.forEach { variants ->
            if (variants.size > 1) {
                val targetName = variants.first()
                variants.drop(1).forEach { oldName ->
                    messageDao.renameChat(oldName, targetName)
                    mergedCount++
                }
            }
        }

        mergedCount
    }
}
