package com.replypal.ultimate.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val note: String = "",
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val body: String,
    val direction: MessageDirection,
    val timestamp: Long = System.currentTimeMillis()
)

enum class MessageDirection { INCOMING, OUTGOING }

@Entity(tableName = "suggestions")
data class SuggestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val message: String,
    val style: ReplyStyle,
    val createdAt: Long = System.currentTimeMillis(),
    val wasCopied: Boolean = false,
    val wasSaved: Boolean = false
)

enum class ReplyStyle(val label: String, val emoji: String) {
    FLIRTY("Flirty", "✦"), FRIENDLY("Friendly", "☺"), FUNNY("Funny", "◌"),
    CARING("Caring", "♥"), CONFIDENT("Confident", "◆")
}

class AppConverters {
    @androidx.room.TypeConverter fun fromDirection(value: MessageDirection) = value.name
    @androidx.room.TypeConverter fun toDirection(value: String) = MessageDirection.valueOf(value)
    @androidx.room.TypeConverter fun fromStyle(value: ReplyStyle) = value.name
    @androidx.room.TypeConverter fun toStyle(value: String) = ReplyStyle.valueOf(value)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY isPinned DESC, lastUpdated DESC")
    fun observeActive(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ConversationEntity): Long

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun clear()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeForConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(item: MessageEntity): Long

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearForConversation(conversationId: Long)
}

@Dao
interface SuggestionDao {
    @Query("SELECT * FROM suggestions WHERE conversationId = :conversationId ORDER BY createdAt DESC")
    fun observeForConversation(conversationId: Long): Flow<List<SuggestionEntity>>

    @Insert
    suspend fun insertAll(items: List<SuggestionEntity>)

    @Query("UPDATE suggestions SET wasCopied = 1 WHERE id = :id")
    suspend fun markCopied(id: Long)

    @Query("DELETE FROM suggestions WHERE conversationId = :conversationId")
    suspend fun clearForConversation(conversationId: Long)
}

@Database(entities = [ConversationEntity::class, MessageEntity::class, SuggestionEntity::class], version = 1, exportSchema = true)
@androidx.room.TypeConverters(AppConverters::class)
abstract class ReplyPalDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun suggestions(): SuggestionDao

    companion object {
        @Volatile private var instance: ReplyPalDatabase? = null
        fun get(context: Context): ReplyPalDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ReplyPalDatabase::class.java, "replypal.db")
                .fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}

data class ConversationBundle(
    val conversation: ConversationEntity,
    val messages: List<MessageEntity>,
    val suggestions: List<SuggestionEntity>
)
