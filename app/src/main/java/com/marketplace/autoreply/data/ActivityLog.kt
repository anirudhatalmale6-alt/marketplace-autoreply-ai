package com.marketplace.autoreply.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Activity Log Entity for storing all message interactions
 * Tracks sender, message, timestamp, ChatGPT reply, and status
 */
@Entity(tableName = "activity_log")
data class ActivityLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Sender information
    val senderName: String,

    // Incoming message text
    val messageText: String,

    // Product title (if extracted from notification)
    val productTitle: String = "",

    // Full notification content for reference
    val fullNotification: String = "",

    // Timestamp when message was received
    val timestamp: Long = System.currentTimeMillis(),

    // ChatGPT generated reply (or static reply if AI disabled)
    val generatedReply: String = "",

    // Status of the interaction
    val status: String = ActivityStatus.PENDING,

    // Whether AI was used for this reply
    val usedAI: Boolean = false,

    // Tokens used by ChatGPT (for tracking API usage)
    val tokensUsed: Int = 0,

    // Response time in milliseconds (from message received to reply sent)
    val responseTimeMs: Long = 0,

    // Spam score if message was analyzed for spam
    val spamScore: Int = 0,

    // Spam reasons if detected
    val spamReasons: String = "",

    // Error message if something went wrong
    val errorMessage: String = ""
)

/**
 * Status constants for activity log entries
 */
object ActivityStatus {
    const val PENDING = "pending"      // Message received, processing
    const val REPLIED = "replied"      // Successfully sent reply
    const val IGNORED = "ignored"      // Message was ignored (already replied, etc.)
    const val SPAM = "spam"            // Message detected as spam
    const val ERROR = "error"          // Error occurred during processing
    const val AI_FAILED = "ai_failed"  // AI generation failed, used fallback
}

/**
 * DAO for Activity Log database operations
 */
@Dao
interface ActivityLogDao {

    @Insert
    suspend fun insert(log: ActivityLog): Long

    @Query("SELECT * FROM activity_log ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 100): Flow<List<ActivityLog>>

    @Query("SELECT * FROM activity_log WHERE status = :status ORDER BY timestamp DESC")
    fun getLogsByStatus(status: String): Flow<List<ActivityLog>>

    @Query("SELECT * FROM activity_log WHERE senderName LIKE '%' || :query || '%' OR messageText LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchLogs(query: String): Flow<List<ActivityLog>>

    @Query("SELECT COUNT(*) FROM activity_log WHERE status = :status")
    fun getCountByStatus(status: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM activity_log WHERE timestamp > :since")
    fun getCountSince(since: Long): Flow<Int>

    @Query("SELECT SUM(tokensUsed) FROM activity_log WHERE timestamp > :since")
    fun getTotalTokensSince(since: Long): Flow<Int?>

    @Query("SELECT * FROM activity_log WHERE id = :id")
    suspend fun getById(id: Long): ActivityLog?

    @Query("UPDATE activity_log SET status = :status, generatedReply = :reply, usedAI = :usedAI, tokensUsed = :tokens, responseTimeMs = :responseTime WHERE id = :id")
    suspend fun updateReply(id: Long, status: String, reply: String, usedAI: Boolean, tokens: Int, responseTime: Long)

    @Query("UPDATE activity_log SET status = :status, generatedReply = :reply, usedAI = :usedAI, tokensUsed = :tokens, responseTimeMs = :responseTime, errorMessage = :error WHERE id = :id")
    suspend fun updateReplyWithError(id: Long, status: String, reply: String, usedAI: Boolean, tokens: Int, responseTime: Long, error: String)

    @Query("UPDATE activity_log SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateError(id: Long, status: String, error: String)

    @Query("UPDATE activity_log SET status = :status, spamScore = :score, spamReasons = :reasons WHERE id = :id")
    suspend fun updateSpam(id: Long, status: String, score: Int, reasons: String)

    @Query("DELETE FROM activity_log")
    suspend fun clearAll()

    @Query("DELETE FROM activity_log WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    // Statistics queries
    @Query("SELECT COUNT(*) FROM activity_log")
    fun getTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM activity_log WHERE usedAI = 1")
    fun getAIReplyCount(): Flow<Int>

    @Query("SELECT AVG(responseTimeMs) FROM activity_log WHERE status = 'replied'")
    fun getAverageResponseTime(): Flow<Double?>
}
