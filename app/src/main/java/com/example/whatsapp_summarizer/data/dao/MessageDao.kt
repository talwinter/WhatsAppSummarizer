package com.example.whatsapp_summarizer.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.example.whatsapp_summarizer.data.model.ChatCount
import com.example.whatsapp_summarizer.data.model.Message
import java.util.*

@Dao
interface MessageDao {
    @Insert
    suspend fun insertMessage(message: Message): Long

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): LiveData<List<Message>>

    @Query("SELECT * FROM messages WHERE chatName = :chatName ORDER BY timestamp ASC")
    fun getMessagesByChat(chatName: String): LiveData<List<Message>>

    @Query("SELECT * FROM messages WHERE chatName IN (:chatNames) ORDER BY timestamp ASC")
    fun getMessagesByChatNames(chatNames: List<String>): LiveData<List<Message>>

    @Query("SELECT DISTINCT chatName FROM messages ORDER BY chatName ASC")
    fun getAllChatNames(): LiveData<List<String>>

    @Query("SELECT DISTINCT chatName FROM messages WHERE isGroup = 1 ORDER BY chatName ASC")
    fun getGroupChatNames(): LiveData<List<String>>

    @Query("SELECT chatName, COUNT(*) as count FROM messages WHERE isGroup = 1 GROUP BY chatName ORDER BY count DESC")
    suspend fun getGroupChatMessageCounts(): List<ChatCount>

    @Query("SELECT * FROM messages WHERE chatName = :chatName AND timestamp >= :startOfDay AND timestamp < :endOfDay ORDER BY timestamp ASC")
    suspend fun getMessagesForDate(chatName: String, startOfDay: Long, endOfDay: Long): List<Message>

    @Query("SELECT * FROM messages WHERE chatName = :chatName AND timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    suspend fun getMessagesForTimeRange(chatName: String, startTime: Long, endTime: Long): List<Message>

    @Query("DELETE FROM messages WHERE chatName = :chatName")
    suspend fun deleteChat(chatName: String)

    @Query("DELETE FROM messages WHERE timestamp < :timestamp")
    suspend fun deleteMessagesOlderThan(timestamp: Long)

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getMessageCount(): Int

    @Query("DELETE FROM messages WHERE id NOT IN (SELECT MIN(id) FROM messages GROUP BY chatName, senderName, messageContent, timestamp/1000)")
    suspend fun removeDuplicateMessages(): Int

    @Query("SELECT chatName, COUNT(*) as count FROM messages GROUP BY chatName ORDER BY count DESC")
    suspend fun getChatMessageCounts(): List<ChatCount>

    @Query("SELECT DISTINCT chatName FROM messages")
    suspend fun getAllChatNamesRaw(): List<String>

    @Query("UPDATE messages SET chatName = :newName WHERE chatName = :oldName")
    suspend fun renameChat(oldName: String, newName: String): Int

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE isGroup = 0")
    suspend fun getPersonalMessageCount(): Int

    /** Purges personal (non-group) chats captured before group detection was fixed. */
    @Query("DELETE FROM messages WHERE isGroup = 0")
    suspend fun deletePersonalMessages(): Int
}
