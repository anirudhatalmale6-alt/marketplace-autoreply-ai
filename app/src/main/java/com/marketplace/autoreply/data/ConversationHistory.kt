package com.marketplace.autoreply.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Conversation History Entity for storing message context per customer.
 * This enables ChatGPT to understand the full conversation context,
 * including whether customer confirmed orders, previous questions, etc.
 */
@Entity(tableName = "conversation_history")
data class ConversationMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Unique identifier for the customer (based on sender name + package)
    val customerId: String,

    // Customer's display name
    val customerName: String,

    // Message role: "customer" or "assistant" (our reply)
    val role: String,

    // The actual message content
    val content: String,

    // Product being discussed (if known)
    val productTitle: String = "",

    // Timestamp of the message
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Message roles for conversation history
 */
object MessageRole {
    const val CUSTOMER = "customer"
    const val ASSISTANT = "assistant"
}

/**
 * DAO for Conversation History database operations
 */
@Dao
interface ConversationHistoryDao {

    @Insert
    suspend fun insert(message: ConversationMessage): Long

    /**
     * Get recent conversation history for a specific customer.
     * Limited to last N messages to avoid token overflow.
     */
    @Query("SELECT * FROM conversation_history WHERE customerId = :customerId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(customerId: String, limit: Int = 10): List<ConversationMessage>

    /**
     * Get all messages for a customer (for debugging/display)
     */
    @Query("SELECT * FROM conversation_history WHERE customerId = :customerId ORDER BY timestamp ASC")
    fun getConversationFlow(customerId: String): Flow<List<ConversationMessage>>

    /**
     * Get the count of messages for a customer
     */
    @Query("SELECT COUNT(*) FROM conversation_history WHERE customerId = :customerId")
    suspend fun getMessageCount(customerId: String): Int

    /**
     * Get unique customer count
     */
    @Query("SELECT COUNT(DISTINCT customerId) FROM conversation_history")
    fun getCustomerCount(): Flow<Int>

    /**
     * Delete old messages to prevent database bloat.
     * Keeps only the most recent messages per customer.
     */
    @Query("""
        DELETE FROM conversation_history
        WHERE id NOT IN (
            SELECT id FROM conversation_history
            WHERE customerId = :customerId
            ORDER BY timestamp DESC
            LIMIT :keepCount
        ) AND customerId = :customerId
    """)
    suspend fun trimOldMessages(customerId: String, keepCount: Int = 20)

    /**
     * Delete all messages for a specific customer
     */
    @Query("DELETE FROM conversation_history WHERE customerId = :customerId")
    suspend fun clearCustomerHistory(customerId: String)

    /**
     * Delete all conversation history
     */
    @Query("DELETE FROM conversation_history")
    suspend fun clearAll()

    /**
     * Delete messages older than a certain time
     */
    @Query("DELETE FROM conversation_history WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}
