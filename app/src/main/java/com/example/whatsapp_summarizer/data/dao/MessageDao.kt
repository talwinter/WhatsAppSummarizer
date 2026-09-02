package com.example.whatsapp_summarizer.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.example.whatsapp_summarizer.data.model.ChatCount
import com.example.whatsapp_summarizer.data.model.Message
import java.util.*

@Dao
interface MessageDao {
    /** Returns -1 when the unique index rejected the row as an already-seen message. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
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

    /**
     * Collapses messages captured more than once before the unique index existed.
     * Grouping deliberately excludes timestamp: those rows carry capture times, so
     * copies of one message all differ there. Keeps the earliest row of each.
     */
    @Query("DELETE FROM messages WHERE id NOT IN (SELECT MIN(id) FROM messages GROUP BY chatName, senderName, messageContent)")
    suspend fun removeDuplicateMessages(): Int

    @Query("SELECT chatName, COUNT(*) as count FROM messages GROUP BY chatName ORDER BY count DESC")
    suspend fun getChatMessageCounts(): List<ChatCount>

    @Query("SELECT DISTINCT chatName FROM messages")
    suspend fun getAllChatNamesRaw(): List<String>

    @Query("UPDATE messages SET chatName = :newName WHERE chatName = :oldName")
    suspend fun renameChat(oldName: String, newName: String): Int

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages(): Int

    // ---- Cross-group queries used by alerts, digest, open questions and ask ----
    // All of them restrict to groups, matching what the rest of the app shows.

    /** Everything captured after [since], oldest first. Drives alerts and digests. */
    @Query("SELECT * FROM messages WHERE isGroup = 1 AND timestamp > :since ORDER BY timestamp ASC")
    suspend fun getGroupMessagesSince(since: Long): List<Message>

    @Query("SELECT * FROM messages WHERE isGroup = 1 AND timestamp >= :start AND timestamp <= :end ORDER BY chatName ASC, timestamp ASC")
    suspend fun getGroupMessagesInRange(start: Long, end: Long): List<Message>

    /** Newest first, capped - the starting pool for cross-group question answering. */
    @Query("SELECT * FROM messages WHERE isGroup = 1 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentGroupMessages(limit: Int): List<Message>

    /**
     * Counts the same message already stored with a send time within a window.
     *
     * The unique index catches a re-post that reports an identical send time, but
     * WhatsApp jitters that value by up to a couple of seconds across re-posts - one
     * message was seen twice at 14:12:35, differing only in milliseconds. This is the
     * tolerance check that closes that gap.
     */
    @Query(
        "SELECT COUNT(*) FROM messages WHERE chatName = :chatName " +
            "AND senderName = :senderName AND messageContent = :content " +
            "AND timestamp >= :from AND timestamp <= :to"
    )
    suspend fun countNearDuplicates(
        chatName: String,
        senderName: String,
        content: String,
        from: Long,
        to: Long
    ): Int

    @Query("SELECT COUNT(*) FROM messages WHERE isGroup = 0")
    suspend fun getPersonalMessageCount(): Int

    /** Purges personal (non-group) chats captured before group detection was fixed. */
    @Query("DELETE FROM messages WHERE isGroup = 0")
    suspend fun deletePersonalMessages(): Int
}
