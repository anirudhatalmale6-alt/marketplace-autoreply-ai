package com.marketplace.autoreply.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RepliedUser::class, DebugLog::class, ActivityLog::class, ConversationMessage::class, SuccessfulReply::class, RecentReply::class, UserSession::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun repliedUserDao(): RepliedUserDao
    abstract fun debugLogDao(): DebugLogDao
    abstract fun activityLogDao(): ActivityLogDao
    abstract fun conversationHistoryDao(): ConversationHistoryDao
    abstract fun learningDao(): LearningDao
    abstract fun userSessionDao(): UserSessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "marketplace_autoreply.db"
                ).fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
