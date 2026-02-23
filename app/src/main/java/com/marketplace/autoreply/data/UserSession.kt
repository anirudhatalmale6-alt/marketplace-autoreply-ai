package com.marketplace.autoreply.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "user_sessions")
data class UserSession(
    @PrimaryKey
    val userId: String,
    val customerName: String = "",
    val productType: String = "",
    val conversationState: String = ConversationState.NEW,
    val lastIntent: String = "",
    val conversationStarted: Boolean = false,
    val greetingSent: Boolean = false,
    val priceSent: Boolean = false,
    val deliveryInfoSent: Boolean = false,
    val effectInfoSent: Boolean = false,
    val usageInfoSent: Boolean = false,
    val orderLinkSent: Boolean = false,
    val interested: Boolean = false,
    val confirmed: Boolean = false,
    val firstMessageTime: Long = System.currentTimeMillis(),
    val lastMessageTime: Long = System.currentTimeMillis(),
    val lastReplyTime: Long = 0L,
    val messageCount: Int = 0
)

object ConversationState {
    const val NEW = "NEW"
    const val GREETED = "GREETED"
    const val PRICE_GIVEN = "PRICE_GIVEN"
    const val DELIVERY_ASKED = "DELIVERY_ASKED"
    const val EFFECT_ASKED = "EFFECT_ASKED"
    const val USAGE_ASKED = "USAGE_ASKED"
    const val INTERESTED = "INTERESTED"
    const val CONFIRMED = "CONFIRMED"
    const val SILENT = "SILENT"
}

@Dao
interface UserSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: UserSession)

    @Query("SELECT * FROM user_sessions WHERE userId = :userId")
    suspend fun getSession(userId: String): UserSession?

    @Query("UPDATE user_sessions SET conversationState = :state, lastMessageTime = :time WHERE userId = :userId")
    suspend fun updateState(userId: String, state: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE user_sessions SET lastIntent = :intent, lastMessageTime = :time WHERE userId = :userId")
    suspend fun updateIntent(userId: String, intent: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE user_sessions SET priceSent = 1 WHERE userId = :userId")
    suspend fun markPriceSent(userId: String)

    @Query("UPDATE user_sessions SET deliveryInfoSent = 1 WHERE userId = :userId")
    suspend fun markDeliveryInfoSent(userId: String)

    @Query("UPDATE user_sessions SET effectInfoSent = 1 WHERE userId = :userId")
    suspend fun markEffectInfoSent(userId: String)

    @Query("UPDATE user_sessions SET usageInfoSent = 1 WHERE userId = :userId")
    suspend fun markUsageInfoSent(userId: String)

    @Query("UPDATE user_sessions SET orderLinkSent = 1 WHERE userId = :userId")
    suspend fun markOrderLinkSent(userId: String)

    @Query("UPDATE user_sessions SET greetingSent = 1, conversationStarted = 1, conversationState = 'GREETED' WHERE userId = :userId")
    suspend fun markGreetingSent(userId: String)

    @Query("UPDATE user_sessions SET interested = 1, conversationState = 'INTERESTED' WHERE userId = :userId")
    suspend fun markInterested(userId: String)

    @Query("UPDATE user_sessions SET confirmed = 1, conversationState = 'CONFIRMED' WHERE userId = :userId")
    suspend fun markConfirmed(userId: String)

    @Query("UPDATE user_sessions SET lastReplyTime = :time WHERE userId = :userId")
    suspend fun updateLastReplyTime(userId: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE user_sessions SET messageCount = messageCount + 1, lastMessageTime = :time WHERE userId = :userId")
    suspend fun incrementMessageCount(userId: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE user_sessions SET productType = :product WHERE userId = :userId")
    suspend fun updateProductType(userId: String, product: String)

    @Query("DELETE FROM user_sessions")
    suspend fun clearAll()
}
