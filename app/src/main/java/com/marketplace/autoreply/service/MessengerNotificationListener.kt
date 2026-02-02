package com.marketplace.autoreply.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.marketplace.autoreply.MarketplaceAutoReplyApp
import com.marketplace.autoreply.data.ActivityLog
import com.marketplace.autoreply.data.ActivityStatus
import com.marketplace.autoreply.data.AppLogger
import com.marketplace.autoreply.data.ChatGPTResult
import com.marketplace.autoreply.data.ChatGPTService
import com.marketplace.autoreply.data.ConversationMessage
import com.marketplace.autoreply.data.LearningManager
import com.marketplace.autoreply.data.MessageRole
import com.marketplace.autoreply.data.RepliedUser
import com.marketplace.autoreply.data.SpamDetector
import com.marketplace.autoreply.data.SuccessType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Listens for Messenger notifications and sends auto-replies directly
 * via notification actions - fully in background without opening Messenger.
 *
 * Supports two modes:
 * 1. AI Mode (ChatGPT): Generates intelligent, context-aware replies
 * 2. Static Mode: Uses predefined 3-stage messaging system
 *
 * Features:
 * - Spam detection to avoid wasting API calls
 * - Activity logging for all interactions
 * - Anti-ban safety with random delays
 */
class MessengerNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Track senders currently being processed to prevent duplicates
    private val processingSet = mutableSetOf<String>()
    private val processLock = Any()

    // Services
    private lateinit var chatGPTService: ChatGPTService
    private val spamDetector = SpamDetector()

    companion object {
        private const val TAG = "NotifListener"

        // Package names for Messenger (original and common clones)
        val MESSENGER_PACKAGES = setOf(
            "com.facebook.orca",           // Original Messenger
            "com.facebook.mlite",          // Messenger Lite
        )

        var instance: MessengerNotificationListener? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val app = MarketplaceAutoReplyApp.getInstance()
        chatGPTService = ChatGPTService(app.preferencesManager)
        AppLogger.info(TAG, "Service STARTED", showToast = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
        AppLogger.info(TAG, "Service STOPPED", showToast = true)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLogger.info(TAG, "Listener connected - ready!", showToast = true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        AppLogger.warn(TAG, "Listener disconnected", showToast = true)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName

        // Log notifications for debugging
        AppLogger.info(TAG, "Notif: $packageName")

        // Check if this is from Messenger (original or clone)
        if (!isMessengerPackage(packageName)) {
            return
        }

        AppLogger.info(TAG, "MESSENGER detected!", showToast = true)

        // Log available actions for debugging
        NotificationReplyHelper.logNotificationActions(sbn.notification)

        scope.launch {
            try {
                processNotification(sbn)
            } catch (e: Exception) {
                AppLogger.error(TAG, "Error: ${e.message}", showToast = true)
            }
        }
    }

    private suspend fun processNotification(sbn: StatusBarNotification) {
        val app = MarketplaceAutoReplyApp.getInstance()
        val startTime = System.currentTimeMillis()

        // Check if auto-reply is enabled
        val isEnabled = app.preferencesManager.isAutoReplyEnabled.first()
        if (!isEnabled) {
            AppLogger.info(TAG, "Auto-reply disabled")
            return
        }

        val notification = sbn.notification
        val extras = notification.extras

        // Extract notification details
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString() ?: ""

        val fullText = "$title $text $bigText $conversationTitle"
        val messageText = text.ifEmpty { bigText }

        AppLogger.info(TAG, "From: $title | Msg: $text")

        // Skip system/group notifications without a clear sender
        if (title.isEmpty() || title == "Messenger" || title.contains("conversation")) {
            AppLogger.info(TAG, "Skipping system notification")
            return
        }

        // Skip chat head notifications - DO NOT interact with chat heads
        val lowerTitle = title.lowercase()
        val lowerText = text.lowercase()
        if (lowerTitle.contains("chat head") || lowerTitle.contains("chathead") ||
            lowerTitle.contains("bubble") || lowerText.contains("chat head") ||
            lowerText.contains("chathead") || lowerText.contains("active chat") ||
            text.contains("Chat heads active") || title.contains("Chat heads")) {
            AppLogger.info(TAG, "Skipping chat heads notification")
            return
        }

        AppLogger.info(TAG, "Message from: $title", showToast = true)

        // Create identifier for this sender
        val senderId = createSenderId(title, sbn.packageName)

        // Check if currently processing this sender (prevents duplicate concurrent processing)
        synchronized(processLock) {
            if (processingSet.contains(senderId)) {
                AppLogger.info(TAG, "Already processing $title")
                return
            }
            // Mark as processing immediately
            processingSet.add(senderId)
        }

        // Extract product title from notification (often in conversation title or message)
        val productTitle = extractProductTitle(conversationTitle, fullText)

        // Create activity log entry
        val activityLog = ActivityLog(
            senderName = title,
            messageText = messageText,
            productTitle = productTitle,
            fullNotification = fullText,
            timestamp = startTime,
            status = ActivityStatus.PENDING
        )
        val logId = app.database.activityLogDao().insert(activityLog)

        // Check for spam if enabled
        val isSpamDetectionEnabled = app.preferencesManager.isSpamDetectionEnabled.first()
        if (isSpamDetectionEnabled) {
            val spamResult = spamDetector.analyzeMessage(messageText)
            if (spamResult.isSpam) {
                AppLogger.warn(TAG, "SPAM detected: ${spamResult.reasons.joinToString()}", showToast = true)
                app.database.activityLogDao().updateSpam(
                    id = logId,
                    status = ActivityStatus.SPAM,
                    score = spamResult.spamScore,
                    reasons = spamResult.reasons.joinToString("; ")
                )
                synchronized(processLock) { processingSet.remove(senderId) }
                return
            }
        }

        // Check if AI mode is enabled FIRST - AI mode has no stage limits
        val isAIEnabled = app.preferencesManager.isAIEnabled.first()

        // Get existing user record to determine current stage
        val existingUser = app.database.repliedUserDao().getUser(senderId)
        val currentStage = existingUser?.currentStage ?: 0 // 0 means new user

        // Determine next stage - but ONLY enforce limits in static mode
        val nextStage = when (currentStage) {
            0 -> 1  // New user -> Stage 1 (Welcome)
            1 -> 2  // Was at Stage 1 -> Stage 2 (Follow-up)
            2 -> 3  // Was at Stage 2 -> Stage 3 (Contact sharing)
            3 -> {
                // In AI mode, continue replying without limits
                if (isAIEnabled) {
                    AppLogger.info(TAG, "AI Mode: continuing conversation (no stage limit)")
                    3 // Stay at stage 3, but continue replying
                } else {
                    // Static mode: stop after 3 stages
                    AppLogger.info(TAG, "All stages completed for $title")
                    app.database.activityLogDao().updateError(logId, ActivityStatus.IGNORED, "All stages completed")
                    synchronized(processLock) { processingSet.remove(senderId) }
                    return
                }
            }
            else -> {
                if (isAIEnabled) {
                    1 // Reset to stage 1 in AI mode
                } else {
                    AppLogger.info(TAG, "Invalid stage for $title")
                    app.database.activityLogDao().updateError(logId, ActivityStatus.IGNORED, "Invalid stage")
                    synchronized(processLock) { processingSet.remove(senderId) }
                    return
                }
            }
        }

        AppLogger.info(TAG, "Stage $currentStage -> $nextStage for $title (AI: $isAIEnabled)", showToast = true)

        // Check available methods
        val hasReplyActionAvailable = NotificationReplyHelper.hasReplyAction(notification)
        val hasAccessibility = MessengerAccessibilityService.instance != null
        AppLogger.info(TAG, "Reply action: $hasReplyActionAvailable, Accessibility: $hasAccessibility")

        if (!hasReplyActionAvailable && !hasAccessibility) {
            AppLogger.warn(TAG, "No reply method available!", showToast = true)
            app.database.activityLogDao().updateError(logId, ActivityStatus.ERROR, "No reply method available")
            synchronized(processLock) { processingSet.remove(senderId) }
            return
        }

        // Determine reply message - AI or Static (isAIEnabled already checked above)
        var replyMessage: String
        var usedAI = false
        var tokensUsed = 0
        var aiError: String? = null  // Track AI errors for logging

        // Save incoming customer message to conversation history
        app.database.conversationHistoryDao().insert(
            ConversationMessage(
                customerId = senderId,
                customerName = title,
                role = MessageRole.CUSTOMER,
                content = messageText,
                productTitle = productTitle
            )
        )

        // Initialize learning manager
        val learningManager = LearningManager(app.database.learningDao())

        if (isAIEnabled) {
            // Try ChatGPT first
            val apiKey = app.preferencesManager.openAIApiKey.first()
            AppLogger.info(TAG, "AI Mode ON, API Key: ${if (apiKey.length > 10) "${apiKey.take(8)}...${apiKey.takeLast(4)}" else "NOT SET"}", showToast = true)

            if (apiKey.isNotBlank()) {
                val promptConfig = app.preferencesManager.getPromptConfig()

                // Fetch conversation history for context (last 8 messages)
                val conversationHistory = app.database.conversationHistoryDao()
                    .getRecentMessages(senderId, limit = 8)

                // Fetch successful examples for few-shot learning
                val successfulExamples = learningManager.getFewShotExamples(limit = 3)

                // Fetch recent replies to avoid repetition
                val recentReplies = learningManager.getRecentReplies(senderId)

                val historyCount = conversationHistory.size
                val examplesCount = successfulExamples.size
                AppLogger.info(TAG, "ChatGPT: $historyCount history, $examplesCount examples", showToast = true)

                when (val result = chatGPTService.generateReply(
                    senderName = title,
                    productTitle = productTitle,
                    messageText = messageText,
                    fullNotification = fullText,
                    apiKey = apiKey,
                    promptConfig = promptConfig,
                    conversationHistory = conversationHistory,
                    successfulExamples = successfulExamples,
                    recentReplies = recentReplies
                )) {
                    is ChatGPTResult.Success -> {
                        replyMessage = result.reply
                        usedAI = true
                        tokensUsed = result.tokensUsed
                        AppLogger.info(TAG, "AI reply OK (${tokensUsed} tokens)", showToast = true)

                        // Save AI reply to conversation history
                        app.database.conversationHistoryDao().insert(
                            ConversationMessage(
                                customerId = senderId,
                                customerName = title,
                                role = MessageRole.ASSISTANT,
                                content = replyMessage,
                                productTitle = productTitle
                            )
                        )

                        // Track this reply to prevent repetition
                        learningManager.trackReply(replyMessage, senderId)

                        // Auto-detect success patterns and learn from them
                        val lowerMessage = messageText.lowercase()
                        if (lowerMessage.contains("ok") || lowerMessage.contains("نعم") ||
                            lowerMessage.contains("واخا") || lowerMessage.contains("yes") ||
                            lowerMessage.contains("أريد") || lowerMessage.contains("بغيت") ||
                            lowerMessage.contains("order") || lowerMessage.contains("buy") ||
                            lowerMessage.contains("اشتري") || lowerMessage.contains("confirm")) {
                            // This looks like a positive response - learn from previous reply
                            val previousReplies = conversationHistory.filter { it.role == MessageRole.ASSISTANT }
                            if (previousReplies.isNotEmpty()) {
                                val lastReply = previousReplies.first()
                                val lastCustomerMsg = conversationHistory.filter { it.role == MessageRole.CUSTOMER }.getOrNull(1)
                                if (lastCustomerMsg != null) {
                                    learningManager.recordSuccess(
                                        customerMessage = lastCustomerMsg.content,
                                        ourReply = lastReply.content,
                                        successType = SuccessType.POSITIVE_RESPONSE,
                                        productContext = productTitle
                                    )
                                    AppLogger.info(TAG, "Learned from successful reply!", showToast = true)
                                }
                            }
                        }

                        // Trim old messages to prevent database bloat
                        app.database.conversationHistoryDao().trimOldMessages(senderId, keepCount = 20)
                        learningManager.cleanup()
                    }
                    is ChatGPTResult.Error -> {
                        // Fallback to static message
                        aiError = result.message
                        AppLogger.error(TAG, "ChatGPT ERROR: $aiError", showToast = true)
                        replyMessage = getStaticMessage(app, nextStage)
                    }
                }
            } else {
                // API key not set, use static
                aiError = "API key not configured"
                AppLogger.warn(TAG, "API key NOT SET - using static", showToast = true)
                replyMessage = getStaticMessage(app, nextStage)
            }
        } else {
            // AI disabled, use static 3-stage messages
            AppLogger.info(TAG, "AI Mode OFF - using static", showToast = true)
            replyMessage = getStaticMessage(app, nextStage)
        }

        AppLogger.info(TAG, "Reply: ${replyMessage.take(50)}...", showToast = true)

        // Get configurable delay range
        val minDelay = app.preferencesManager.minDelaySeconds.first()
        val maxDelay = app.preferencesManager.maxDelaySeconds.first()
        val delayMs = Random.nextLong(minDelay * 1000L, (maxDelay + 1) * 1000L)
        AppLogger.info(TAG, "Waiting ${delayMs/1000}s...", showToast = true)
        delay(delayMs)

        // Check if notification has direct reply action
        val hasReplyAction = NotificationReplyHelper.hasReplyAction(notification)
        AppLogger.info(TAG, "Has reply action: $hasReplyAction", showToast = true)

        // Try Method 1: Direct Reply (TRUE BACKGROUND - no opening Messenger)
        if (hasReplyAction) {
            AppLogger.info(TAG, "Trying direct reply (background)...", showToast = true)
            val directReplySuccess = NotificationReplyHelper.sendDirectReply(
                this@MessengerNotificationListener, sbn, replyMessage
            )

            if (directReplySuccess) {
                // Direct reply was sent - record success with stage
                val responseTime = System.currentTimeMillis() - startTime
                recordSuccessfulReply(senderId, title, conversationTitle, sbn.packageName, nextStage, existingUser)

                // Preserve AI_FAILED status if AI failed but fallback was used
                val finalStatus = if (aiError != null) ActivityStatus.AI_FAILED else ActivityStatus.REPLIED
                app.database.activityLogDao().updateReplyWithError(
                    id = logId,
                    status = finalStatus,
                    reply = replyMessage,
                    usedAI = usedAI,
                    tokens = tokensUsed,
                    responseTime = responseTime,
                    error = aiError ?: ""
                )

                val statusMsg = if (usedAI) "AI reply" else if (aiError != null) "Fallback (AI failed)" else "Static"
                AppLogger.info(TAG, "$statusMsg sent to: $title", showToast = true)
                synchronized(processLock) { processingSet.remove(senderId) }
                return
            }
        }

        // Method 1 failed or not available - try Method 2: Accessibility Service
        AppLogger.info(TAG, "Using accessibility method...", showToast = true)

        val accessibilityService = MessengerAccessibilityService.instance
        if (accessibilityService == null) {
            AppLogger.error(TAG, "Enable Accessibility Service!", showToast = true)
            app.database.activityLogDao().updateError(logId, ActivityStatus.ERROR, "Accessibility not enabled")
            synchronized(processLock) { processingSet.remove(senderId) }
            return
        }

        // Open the notification to launch Messenger
        val contentIntent = sbn.notification.contentIntent
        if (contentIntent == null) {
            AppLogger.error(TAG, "No content intent!", showToast = true)
            app.database.activityLogDao().updateError(logId, ActivityStatus.ERROR, "No content intent")
            synchronized(processLock) { processingSet.remove(senderId) }
            return
        }

        // Queue the reply for accessibility service
        AppLogger.info(TAG, "Queuing reply...", showToast = true)
        var accessibilitySuccess = false
        accessibilityService.requestSendReply(
            message = replyMessage,
            targetPackage = sbn.packageName,
            senderName = title
        ) { result ->
            accessibilitySuccess = result
        }

        // Open Messenger by clicking notification
        try {
            contentIntent.send()
            AppLogger.info(TAG, "Opening Messenger...", showToast = true)
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to open: ${e.message}", showToast = true)
            MessengerAccessibilityService.pendingReply = null
            app.database.activityLogDao().updateError(logId, ActivityStatus.ERROR, "Failed to open: ${e.message}")
            synchronized(processLock) { processingSet.remove(senderId) }
            return
        }

        // Wait for accessibility to complete (max 25 seconds for cloned apps)
        var waitTime = 0L
        while (MessengerAccessibilityService.pendingReply != null && waitTime < 25000) {
            delay(500)
            waitTime += 500
        }

        val responseTime = System.currentTimeMillis() - startTime
        if (accessibilitySuccess) {
            recordSuccessfulReply(senderId, title, conversationTitle, sbn.packageName, nextStage, existingUser)

            // Preserve AI_FAILED status if AI failed but fallback was used
            val finalStatus = if (aiError != null) ActivityStatus.AI_FAILED else ActivityStatus.REPLIED
            app.database.activityLogDao().updateReplyWithError(
                id = logId,
                status = finalStatus,
                reply = replyMessage,
                usedAI = usedAI,
                tokens = tokensUsed,
                responseTime = responseTime,
                error = aiError ?: ""
            )

            val statusMsg = if (usedAI) "AI reply" else if (aiError != null) "Fallback (AI failed)" else "Static"
            AppLogger.info(TAG, "$statusMsg sent via accessibility to: $title", showToast = true)
        } else {
            AppLogger.error(TAG, "Reply failed - check Accessibility", showToast = true)
            app.database.activityLogDao().updateError(logId, ActivityStatus.ERROR, "Accessibility reply failed")
        }

        // Always remove from processing set when done
        synchronized(processLock) { processingSet.remove(senderId) }
    }

    private suspend fun getStaticMessage(app: MarketplaceAutoReplyApp, stage: Int): String {
        val stage1Messages = app.preferencesManager.stage1Messages.first()
        val stage2Messages = app.preferencesManager.stage2Messages.first()
        val stage3Messages = app.preferencesManager.stage3Messages.first()

        return app.preferencesManager.getMessageForStage(
            stage = stage,
            stage1List = stage1Messages,
            stage2List = stage2Messages,
            stage3List = stage3Messages
        )
    }

    private fun extractProductTitle(conversationTitle: String, fullText: String): String {
        // Try to extract product name from notification
        // Common patterns: "Product Name - Marketplace", "Re: Product Name", etc.

        if (conversationTitle.isNotEmpty() && conversationTitle != "Marketplace") {
            // Clean up conversation title
            return conversationTitle
                .replace("Marketplace", "")
                .replace("Re:", "")
                .replace("-", "")
                .trim()
        }

        // Try to find product-related keywords in the full text
        val productPatterns = listOf(
            Regex("(?:about|interested in|is the|price of)\\s+(.+?)(?:\\?|\\.|$)", RegexOption.IGNORE_CASE),
            Regex("(?:this|the)\\s+(.+?)\\s+(?:still|available|for sale)", RegexOption.IGNORE_CASE)
        )

        for (pattern in productPatterns) {
            val match = pattern.find(fullText)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1].trim()
            }
        }

        return ""
    }

    private suspend fun recordSuccessfulReply(
        senderId: String,
        senderName: String,
        conversationTitle: String,
        packageName: String,
        stage: Int,
        existingUser: RepliedUser?
    ) {
        val app = MarketplaceAutoReplyApp.getInstance()

        if (existingUser != null) {
            // Update existing user with new stage
            app.database.repliedUserDao().updateStage(
                identifier = senderId,
                stage = stage,
                timestamp = System.currentTimeMillis()
            )
            AppLogger.info(TAG, "Updated $senderName to stage $stage", showToast = true)
        } else {
            // Insert new user at stage 1
            app.database.repliedUserDao().insert(
                RepliedUser(
                    odentifier = senderId,
                    senderName = senderName,
                    conversationTitle = conversationTitle.ifEmpty { senderName },
                    messengerPackage = packageName,
                    currentStage = stage,
                    interactionCount = 1
                )
            )
            AppLogger.info(TAG, "New user $senderName at stage $stage", showToast = true)
        }
    }

    private fun isMessengerPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase()

        // Check exact matches
        if (packageName in MESSENGER_PACKAGES) return true

        // Dynamic detection for ANY Messenger clone
        // Pattern 1: Any package starting with com.facebook.orc (orca, orcb, orcc, orcd, etc.)
        if (pkg.startsWith("com.facebook.orc")) return true

        // Pattern 2: Any package containing "messenger"
        if (pkg.contains("messenger")) return true

        // Pattern 3: Facebook Lite variants
        if (pkg.startsWith("com.facebook.mlite")) return true
        if (pkg.contains("facebook") && pkg.contains("lite")) return true

        // Pattern 4: Clone app patterns (Parallel Space, Dual Space, etc.)
        // These often wrap packages or add suffixes
        if (pkg.contains("facebook.orca")) return true
        if (pkg.contains("facebook.orc")) return true

        // Pattern 5: Common clone app prefixes/suffixes
        if (pkg.contains("clone") && pkg.contains("facebook")) return true
        if (pkg.contains("dual") && pkg.contains("facebook")) return true
        if (pkg.contains("parallel") && pkg.contains("facebook")) return true

        // Pattern 6: Generic - any facebook package with messaging capability
        // Check notification category as backup (handled elsewhere)

        return false
    }

    private fun createSenderId(senderName: String, packageName: String): String {
        return "${senderName.lowercase().trim()}_${packageName.hashCode()}"
    }
}
