package com.example.whatsapp_summarizer.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.whatsapp_summarizer.data.dao.MessageDao
import com.example.whatsapp_summarizer.data.model.Message

@Database(entities = [Message::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Step 1: Get all unique chat names
                val cursor = database.query("SELECT DISTINCT chatName FROM messages")
                val names = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    names.add(cursor.getString(0))
                }
                cursor.close()

                // Step 2: Group by normalized name
                val groups = mutableMapOf<String, MutableList<String>>()
                names.forEach { name ->
                    val normalized = normalizeName(name)
                    groups.getOrPut(normalized) { mutableListOf() }.add(name)
                }

                // Step 3: Merge groups with multiple variations
                groups.values.forEach { variants ->
                    if (variants.size > 1) {
                        val target = variants.first()
                        variants.drop(1).forEach { oldName ->
                            database.execSQL(
                                "UPDATE messages SET chatName = ? WHERE chatName = ?",
                                arrayOf(target, oldName)
                            )
                        }
                    }
                }
            }

            private fun normalizeName(name: String): String {
                var normalized = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                normalized = normalized.replace(Regex("[^\\p{ASCII}]"), "")
                normalized = normalized.replace(Regex("[^a-zA-Z0-9 ]"), " ")
                return normalized
                    .replace(Regex(" +"), " ")
                    .trim()
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Fix chat names that contain WhatsApp message count suffixes like "(2 messages)", "(5 messages)"
                val cursor = database.query("SELECT DISTINCT chatName FROM messages")
                val namesToFix = mutableListOf<Pair<String, String>>() // oldName -> newName
                
                while (cursor.moveToNext()) {
                    val chatName = cursor.getString(0)
                    val cleaned = chatName.replace(Regex("\\s*\\(\\d+\\s+(new\\s+)?messages?\\)\\s*$"), "").trim()
                    if (cleaned != chatName && cleaned.isNotBlank()) {
                        namesToFix.add(chatName to cleaned)
                    }
                }
                cursor.close()

                // Apply fixes - merge duplicates by grouping cleaned names
                val targetNames = mutableMapOf<String, String>() // cleaned -> first old name (to keep)
                val updates = mutableListOf<Pair<String, String>>() // oldName -> targetName

                namesToFix.forEach { (oldName, cleaned) ->
                    val existingTarget = targetNames[cleaned]
                    if (existingTarget == null) {
                        // First occurrence - keep this one, just rename it
                        targetNames[cleaned] = oldName
                        updates.add(oldName to cleaned)
                    } else {
                        // Duplicate - merge into the first one
                        updates.add(oldName to cleaned)
                    }
                }

                updates.forEach { (oldName, newName) ->
                    database.execSQL(
                        "UPDATE messages SET chatName = ? WHERE chatName = ?",
                        arrayOf(newName, oldName)
                    )
                }
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Step 1: Get all unique chat names
                val cursor = database.query("SELECT DISTINCT chatName FROM messages")
                val names = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    names.add(cursor.getString(0))
                }
                cursor.close()

                // Step 2: Build normalized mapping using the same logic as ChatNameNormalizer
                val normalizedMap = mutableMapOf<String, MutableList<String>>()
                names.forEach { name ->
                    val normalized = normalizeUsingChatNameNormalizer(name)
                    normalizedMap.getOrPut(normalized) { mutableListOf() }.add(name)
                }

                // Step 3: For each group with multiple variants, merge into the normalized name
                normalizedMap.values.forEach { variants ->
                    if (variants.size > 1) {
                        val targetName = normalizeUsingChatNameNormalizer(variants.first())
                        variants.forEach { oldName ->
                            database.execSQL(
                                "UPDATE messages SET chatName = ? WHERE chatName = ?",
                                arrayOf(targetName, oldName)
                            )
                        }
                    } else if (variants.size == 1) {
                        // Even single entries might need re-normalization
                        val oldName = variants.first()
                        val newName = normalizeUsingChatNameNormalizer(oldName)
                        if (newName != oldName) {
                            database.execSQL(
                                "UPDATE messages SET chatName = ? WHERE chatName = ?",
                                arrayOf(newName, oldName)
                            )
                        }
                    }
                }
            }

            private fun normalizeUsingChatNameNormalizer(name: String): String {
                // Match ChatNameNormalizer.normalize() logic exactly
                if (name.isBlank()) return "Unknown"
                
                // Strip WhatsApp message count suffixes like "(2 messages)", "(5 messages)"
                var result = name.trim()
                    .replace(Regex("\\s*\\(\\d+\\s+(new\\s+)?messages?\\)\\s*$"), "")
                
                // Replace ALL Unicode control, format, and space characters with regular space
                result = result.replace(Regex("[\\p{Cc}\\p{Cf}\\p{Zs}]+"), " ")
                
                // Collapse multiple spaces and trim
                result = result.replace(Regex(" +"), " ").trim()
                
                return result.ifEmpty { name.trim() }
            }
        }

        /**
         * Makes duplicate messages structurally impossible.
         *
         * WhatsApp re-posts every active conversation notification whenever any new
         * message arrives anywhere, so the listener saw the same message repeatedly -
         * one was captured 24 times over three hours. Rows are now keyed by WhatsApp's
         * own send time, so a re-post collides with the row it already wrote.
         *
         * Existing rows were stamped with capture time, which cannot be recovered, so
         * copies of one message all differ on timestamp. They are collapsed by
         * (chat, sender, content) keeping the earliest - closest to the real send
         * time - and only then can the unique index be created.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    DELETE FROM messages WHERE id NOT IN (
                        SELECT MIN(id) FROM messages
                        GROUP BY chatName, senderName, messageContent
                    )
                    """.trimIndent()
                )
                // Name must match what Room derives from the @Entity index, or the
                // schema validation on open will fail.
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_messages_chatName_senderName_messageContent_timestamp " +
                        "ON messages (chatName, senderName, messageContent, timestamp)"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "whatsapp_summarizer_db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
