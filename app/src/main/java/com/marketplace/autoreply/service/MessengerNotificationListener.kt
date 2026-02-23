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
import com.marketplace.autoreply.data.ConversationState
import com.marketplace.autoreply.data.CustomerIntent
import com.marketplace.autoreply.data.DarijaIntentDetector
import com.marketplace.autoreply.data.LearningManager
import com.marketplace.autoreply.data.MessageRole
import com.marketplace.autoreply.data.RepliedUser
import com.marketplace.autoreply.data.SpamDetector
import com.marketplace.autoreply.data.SuccessType
import com.marketplace.autoreply.data.UserSession
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

        // Check if AI mode is enabled
        val isAIEnabled = app.preferencesManager.isAIEnabled.first()

        // Get existing user record for stage tracking (static mode)
        val existingUser = app.database.repliedUserDao().getUser(senderId)
        val currentStage = existingUser?.currentStage ?: 0
        val nextStage = if (isAIEnabled) {
            // AI mode: no stage limits
            if (currentStage >= 3) 3 else currentStage + 1
        } else {
            // Static mode: enforce 3-stage limit
            when (currentStage) {
                0 -> 1; 1 -> 2; 2 -> 3
                3 -> {
                    AppLogger.info(TAG, "All stages completed for $title")
                    app.database.activityLogDao().updateError(logId, ActivityStatus.IGNORED, "All stages completed")
                    synchronized(processLock) { processingSet.remove(senderId) }
                    return
                }
                else -> {
                    app.database.activityLogDao().updateError(logId, ActivityStatus.IGNORED, "Invalid stage")
                    synchronized(processLock) { processingSet.remove(senderId) }
                    return
                }
            }
        }

        // Check available reply methods
        val hasReplyActionAvailable = NotificationReplyHelper.hasReplyAction(notification)
        val hasAccessibility = MessengerAccessibilityService.instance != null
        if (!hasReplyActionAvailable && !hasAccessibility) {
            AppLogger.warn(TAG, "No reply method available!", showToast = true)
            app.database.activityLogDao().updateError(logId, ActivityStatus.ERROR, "No reply method available")
            synchronized(processLock) { processingSet.remove(senderId) }
            return
        }

        var replyMessage: String
        var usedAI = false
        var tokensUsed = 0
        var aiError: String? = null

        // Save incoming message to conversation history
        app.database.conversationHistoryDao().insert(
            ConversationMessage(
                customerId = senderId,
                customerName = title,
                role = MessageRole.CUSTOMER,
                content = messageText,
                productTitle = productTitle
            )
        )

        val learningManager = LearningManager(app.database.learningDao())
        val intentDetector = DarijaIntentDetector()

        if (isAIEnabled) {
            val apiKey = app.preferencesManager.openAIApiKey.first()

            if (apiKey.isNotBlank()) {
                // === V5.0 PIPELINE ===

                // Step 1: Detect intent from Darija/French/Arabic keywords
                val detectedIntent = intentDetector.detectIntent(messageText)
                AppLogger.info(TAG, "Intent: ${detectedIntent.intent} (${detectedIntent.matchedKeyword})", showToast = true)

                // Step 2: Get/create user session
                var session = app.database.userSessionDao().getSession(senderId)
                if (session == null) {
                    session = UserSession(
                        userId = senderId,
                        customerName = title,
                        productType = productTitle,
                        conversationState = ConversationState.NEW
                    )
                    app.database.userSessionDao().upsert(session)
                }
                app.database.userSessionDao().incrementMessageCount(senderId)

                // Update product type if we have one
                if (productTitle.isNotEmpty() && session.productType.isEmpty()) {
                    app.database.userSessionDao().updateProductType(senderId, productTitle)
                    session = session.copy(productType = productTitle)
                }

                // Step 3: Update session intent
                app.database.userSessionDao().updateIntent(senderId, detectedIntent.intent)

                // Step 4: Fetch admin settings
                val price = app.preferencesManager.productPrice.first()
                val orderLink = app.preferencesManager.orderLink.first()
                val phone = app.preferencesManager.phoneNumber.first()

                // Step 5: Fetch context data
                val conversationHistory = app.database.conversationHistoryDao()
                    .getRecentMessages(senderId, limit = 8)
                val successfulExamples = learningManager.getFewShotExamples(limit = 3)
                val recentReplies = learningManager.getRecentReplies(senderId)
                val promptConfig = app.preferencesManager.getPromptConfig()

                AppLogger.info(TAG, "State: ${session.conversationState}, History: ${conversationHistory.size}", showToast = true)

                // Step 6: Build context and call ChatGPT
                val msgContext = ChatGPTService.MessageContext(
                    senderName = title,
                    productTitle = productTitle.ifEmpty { session.productType },
                    messageText = messageText,
                    fullNotification = fullText,
                    detectedIntent = detectedIntent.intent,
                    matchedKeyword = detectedIntent.matchedKeyword,
                    conversationState = session.conversationState,
                    session = session,
                    price = price,
                    orderLink = orderLink,
                    phone = phone,
                    conversationHistory = conversationHistory,
                    successfulExamples = successfulExamples,
                    recentReplies = recentReplies
                )

                when (val result = chatGPTService.generateReply(msgContext, apiKey, promptConfig)) {
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

                        // Track reply for anti-repetition
                        learningManager.trackReply(replyMessage, senderId)

                        // Step 7: Update session state based on intent
                        when (detectedIntent.intent) {
                            CustomerIntent.GREETING -> {
                                app.database.userSessionDao().markGreetingSent(senderId)
                            }
                            CustomerIntent.PRICE -> {
                                app.database.userSessionDao().markPriceSent(senderId)
                                app.database.userSessionDao().updateState(senderId, ConversationState.PRICE_GIVEN)
                            }
                            CustomerIntent.DELIVERY -> {
                                app.database.userSessionDao().markDeliveryInfoSent(senderId)
                                app.database.userSessionDao().updateState(senderId, ConversationState.DELIVERY_ASKED)
                            }
                            CustomerIntent.EFFECT -> {
                                app.database.userSessionDao().markEffectInfoSent(senderId)
                                app.database.userSessionDao().updateState(senderId, ConversationState.EFFECT_ASKED)
                            }
                            CustomerIntent.USAGE -> {
                                app.database.userSessionDao().markUsageInfoSent(senderId)
                                app.database.userSessionDao().updateState(senderId, ConversationState.USAGE_ASKED)
                            }
                            CustomerIntent.BUY, CustomerIntent.CONFIRM -> {
                                app.database.userSessionDao().markInterested(senderId)
                                app.database.userSessionDao().markOrderLinkSent(senderId)
                                // Learn from previous reply that led to this
                                val prevReplies = conversationHistory.filter { it.role == MessageRole.ASSISTANT }
                                val prevCustomerMsgs = conversationHistory.filter { it.role == MessageRole.CUSTOMER }
                                if (prevReplies.isNotEmpty() && prevCustomerMsgs.size > 1) {
                                    learningManager.recordSuccess(
                                        customerMessage = prevCustomerMsgs[1].content,
                                        ourReply = prevReplies.first().content,
                                        successType = SuccessType.ORDER_CONFIRMED,
                                        productContext = productTitle
                                    )
                                    AppLogger.info(TAG, "Learned: reply led to BUY intent!", showToast = true)
                                }
                            }
                        }

                        app.database.userSessionDao().updateLastReplyTime(senderId)
                        app.database.conversationHistoryDao().trimOldMessages(senderId, keepCount = 20)
                        learningManager.cleanup()
                    }
                    is ChatGPTResult.Error -> {
                        aiError = result.message
                        AppLogger.error(TAG, "ChatGPT ERROR: $aiError", showToast = true)
                        replyMessage = getStaticMessage(app, nextStage)
                    }
                }
            } else {
                aiError = "API key not configured"
                AppLogger.warn(TAG, "API key NOT SET - using static", showToast = true)
                replyMessage = getStaticMessage(app, nextStage)
            }
        } else {
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
