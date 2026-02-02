package com.marketplace.autoreply.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Successful Reply Entity - stores replies that led to positive outcomes.
 * These are used as few-shot examples for ChatGPT to learn from.
 */
@Entity(tableName = "successful_replies")
data class SuccessfulReply(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // The customer's message/question
    val customerMessage: String,

    // The successful reply that worked
    val successfulResponse: String,

    // What made it successful (order_confirmed, positive_response, continued_interest)
    val successType: String,

    // Product category context
    val productContext: String = "",

    // How many times this pattern was successful
    val successCount: Int = 1,

    // Timestamp
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Success types for categorizing what made a reply work
 */
object SuccessType {
    const val ORDER_CONFIRMED = "order_confirmed"      // Customer confirmed purchase
    const val POSITIVE_RESPONSE = "positive_response"  // Customer responded positively
    const val CONTINUED_INTEREST = "continued_interest" // Customer asked follow-up questions
    const val CONTACT_SHARED = "contact_shared"        // Customer moved to WhatsApp/Instagram
}

/**
 * Recent Reply Tracker - prevents repetition by tracking recently used phrases
 */
@Entity(tableName = "recent_replies")
data class RecentReply(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Hash of the reply to quickly check for duplicates
    val replyHash: Int,

    // The actual reply text
    val replyText: String,

    // Customer ID it was sent to
    val customerId: String,

    // Timestamp
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * DAO for Learning System
 */
@Dao
interface LearningDao {

    // === Successful Replies ===

    @Insert
    suspend fun insertSuccessfulReply(reply: SuccessfulReply): Long

    @Query("SELECT * FROM successful_replies ORDER BY successCount DESC, timestamp DESC LIMIT :limit")
    suspend fun getTopSuccessfulReplies(limit: Int = 5): List<SuccessfulReply>

    @Query("SELECT * FROM successful_replies WHERE successType = :type ORDER BY successCount DESC LIMIT :limit")
    suspend fun getSuccessfulRepliesByType(type: String, limit: Int = 3): List<SuccessfulReply>

    @Query("UPDATE successful_replies SET successCount = successCount + 1 WHERE id = :id")
    suspend fun incrementSuccessCount(id: Long)

    @Query("SELECT * FROM successful_replies WHERE customerMessage LIKE '%' || :keyword || '%' LIMIT 3")
    suspend fun findSimilarSuccessfulReplies(keyword: String): List<SuccessfulReply>

    @Query("SELECT COUNT(*) FROM successful_replies")
    fun getSuccessfulReplyCount(): Flow<Int>

    // === Recent Replies (Anti-repetition) ===

    @Insert
    suspend fun insertRecentReply(reply: RecentReply): Long

    @Query("SELECT * FROM recent_replies WHERE customerId = :customerId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentRepliesForCustomer(customerId: String, limit: Int = 5): List<RecentReply>

    @Query("SELECT EXISTS(SELECT 1 FROM recent_replies WHERE replyHash = :hash AND customerId = :customerId AND timestamp > :since)")
    suspend fun wasReplyUsedRecently(hash: Int, customerId: String, since: Long): Boolean

    @Query("DELETE FROM recent_replies WHERE timestamp < :before")
    suspend fun cleanOldRecentReplies(before: Long)

    // === Cleanup ===

    @Query("DELETE FROM successful_replies")
    suspend fun clearAllSuccessfulReplies()

    @Query("DELETE FROM recent_replies")
    suspend fun clearAllRecentReplies()
}

/**
 * Learning Manager - handles the smart learning logic
 */
class LearningManager(private val dao: LearningDao) {

    /**
     * Record a successful interaction for future learning
     */
    suspend fun recordSuccess(
        customerMessage: String,
        ourReply: String,
        successType: String,
        productContext: String = ""
    ) {
        // Check if we already have a similar successful reply
        val existing = dao.findSimilarSuccessfulReplies(customerMessage.take(30))
        val similarReply = existing.find {
            it.successfulResponse.take(50) == ourReply.take(50)
        }

        if (similarReply != null) {
            // Increment the success count
            dao.incrementSuccessCount(similarReply.id)
        } else {
            // Add new successful reply
            dao.insertSuccessfulReply(
                SuccessfulReply(
                    customerMessage = customerMessage,
                    successfulResponse = ourReply,
                    successType = successType,
                    productContext = productContext
                )
            )
        }
    }

    /**
     * Get few-shot examples for ChatGPT prompt
     */
    suspend fun getFewShotExamples(limit: Int = 3): List<SuccessfulReply> {
        return dao.getTopSuccessfulReplies(limit)
    }

    /**
     * Track a reply to prevent repetition
     */
    suspend fun trackReply(reply: String, customerId: String) {
        dao.insertRecentReply(
            RecentReply(
                replyHash = reply.hashCode(),
                replyText = reply,
                customerId = customerId
            )
        )
    }

    /**
     * Check if a reply was used recently for this customer
     */
    suspend fun wasUsedRecently(reply: String, customerId: String): Boolean {
        // Check last 24 hours
        val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        return dao.wasReplyUsedRecently(reply.hashCode(), customerId, oneDayAgo)
    }

    /**
     * Get recent replies for variation check
     */
    suspend fun getRecentReplies(customerId: String): List<String> {
        return dao.getRecentRepliesForCustomer(customerId).map { it.replyText }
    }

    /**
     * Clean up old tracking data
     */
    suspend fun cleanup() {
        // Remove recent replies older than 7 days
        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
        dao.cleanOldRecentReplies(sevenDaysAgo)
    }
}
