package com.marketplace.autoreply.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * ChatGPT API Service for generating intelligent auto-replies
 * Uses OpenAI's GPT API to analyze incoming messages and generate
 * contextual, sales-focused responses for cosmetic products.
 */
class ChatGPTService(private val preferencesManager: PreferencesManager) {

    companion object {
        private const val TAG = "ChatGPT"
        private const val API_URL = "https://api.openai.com/v1/chat/completions"
        private const val MODEL = "gpt-3.5-turbo"
        private const val MAX_TOKENS = 150
        private const val TEMPERATURE = 0.7
    }

    /**
     * Generate a smart reply using ChatGPT based on the incoming message context
     *
     * @param senderName The name of the person who sent the message
     * @param productTitle The product title extracted from notification (if available)
     * @param messageText The actual message content
     * @param fullNotification The full notification text for additional context
     * @param apiKey The OpenAI API key
     * @param promptConfig The user-configured prompt settings
     * @param conversationHistory Previous messages with this customer (newest first)
     * @param successfulExamples Few-shot examples of successful replies
     * @param recentReplies Recent replies to avoid repetition
     * @return Generated reply text or null if failed
     */
    suspend fun generateReply(
        senderName: String,
        productTitle: String,
        messageText: String,
        fullNotification: String,
        apiKey: String,
        promptConfig: PromptConfig,
        conversationHistory: List<ConversationMessage> = emptyList(),
        successfulExamples: List<SuccessfulReply> = emptyList(),
        recentReplies: List<String> = emptyList()
    ): ChatGPTResult = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext ChatGPTResult.Error("API key not configured")
            }

            val systemPrompt = buildSystemPrompt(promptConfig, conversationHistory.isNotEmpty(), successfulExamples, recentReplies)
            val userPrompt = buildUserPrompt(senderName, productTitle, messageText, fullNotification)

            // Build messages array with conversation history
            val messagesArray = JSONArray().apply {
                // System prompt first
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })

                // Add conversation history (reversed to chronological order, oldest first)
                val historyReversed = conversationHistory.reversed()
                for (msg in historyReversed) {
                    val role = if (msg.role == MessageRole.CUSTOMER) "user" else "assistant"
                    put(JSONObject().apply {
                        put("role", role)
                        put("content", msg.content)
                    })
                }

                // Current message last
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }

            val requestBody = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", MAX_TOKENS)
                put("temperature", TEMPERATURE)
                put("messages", messagesArray)
            }

            val connection = URL(API_URL).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
                connectTimeout = 30000
                readTimeout = 30000
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use(BufferedReader::readText)
                val jsonResponse = JSONObject(response)
                val choices = jsonResponse.getJSONArray("choices")
                if (choices.length() > 0) {
                    val reply = choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()

                    // Get usage info for logging
                    val usage = jsonResponse.optJSONObject("usage")
                    val tokensUsed = usage?.optInt("total_tokens") ?: 0

                    AppLogger.info(TAG, "Reply generated (${tokensUsed} tokens)")
                    return@withContext ChatGPTResult.Success(reply, tokensUsed)
                }
                return@withContext ChatGPTResult.Error("Empty response from API")
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                AppLogger.error(TAG, "API Error $responseCode: $errorStream")

                // Parse OpenAI error message for user-friendly display
                val errorMessage = try {
                    val errorJson = JSONObject(errorStream)
                    errorJson.optJSONObject("error")?.optString("message") ?: "Unknown error"
                } catch (e: Exception) {
                    when (responseCode) {
                        401 -> "Invalid API key"
                        429 -> "Rate limit exceeded"
                        500, 502, 503 -> "OpenAI server error"
                        else -> "HTTP $responseCode"
                    }
                }
                return@withContext ChatGPTResult.Error(errorMessage)
            }
        } catch (e: java.net.UnknownHostException) {
            AppLogger.error(TAG, "Network error: No internet")
            return@withContext ChatGPTResult.Error("No internet connection")
        } catch (e: java.net.SocketTimeoutException) {
            AppLogger.error(TAG, "Network error: Timeout")
            return@withContext ChatGPTResult.Error("Connection timeout")
        } catch (e: Exception) {
            AppLogger.error(TAG, "Exception: ${e.message}")
            return@withContext ChatGPTResult.Error("Error: ${e.message?.take(50)}")
        }
    }

    private fun buildSystemPrompt(
        config: PromptConfig,
        hasHistory: Boolean = false,
        successfulExamples: List<SuccessfulReply> = emptyList(),
        recentReplies: List<String> = emptyList()
    ): String {
        val toneDescription = when (config.replyTone) {
            ReplyTone.SALES -> "persuasive and sales-focused, aiming to close the deal"
            ReplyTone.FRIENDLY -> "warm, friendly, and approachable"
            ReplyTone.SHORT -> "brief and to the point, maximum 1-2 sentences"
            ReplyTone.PERSUASIVE -> "highly persuasive, emphasizing benefits and urgency"
        }

        val historyNote = if (hasHistory) """
CONVERSATION CONTEXT:
- You have previous conversation history with this customer
- Use the context to provide relevant, continuous responses
- Remember what they asked before and what you told them
- If they confirmed an order before, acknowledge it
- Don't repeat information you already shared
- Build on the existing conversation naturally
""" else ""

        // Build few-shot examples from successful conversations
        val examplesSection = if (successfulExamples.isNotEmpty()) {
            val examples = successfulExamples.take(3).mapIndexed { index, ex ->
                """
Example ${index + 1} (This reply led to a sale!):
Customer: "${ex.customerMessage.take(100)}"
Good reply: "${ex.successfulResponse.take(150)}"
"""
            }.joinToString("\n")
            """
LEARN FROM THESE SUCCESSFUL REPLIES:
$examples
Use similar style and persuasion techniques in your response.
"""
        } else ""

        // Anti-repetition section
        val antiRepetitionNote = if (recentReplies.isNotEmpty()) {
            val recentExamples = recentReplies.take(3).joinToString("\n- ")
            """
AVOID REPETITION - DO NOT use these phrases you recently used:
- $recentExamples
Create a FRESH, UNIQUE response with different wording.
"""
        } else ""

        return """
You are an EXPERT sales assistant for a marketplace seller. Your goal is to CONVINCE customers to buy.

CUSTOMER PROFILE:
${config.customerProfile}

PRODUCT CATEGORY:
${config.productCategory}
$historyNote$examplesSection$antiRepetitionNote
PERSUASION TECHNIQUES TO USE:
- Create urgency: "This is selling fast" or "Limited stock available"
- Social proof: "Many customers love this product"
- Personal touch: Use their name, be warm and genuine
- Handle objections: Address concerns directly and positively
- Call to action: Guide them to the next step

YOUR RESPONSE STYLE:
- Be $toneDescription
- Keep responses short (1-3 sentences max)
- Sound natural and human, NEVER robotic
- Match the customer's language style (formal/casual)
- Show enthusiasm about the product
- Make them feel special and valued
- Never reveal you are an AI or automated system

SMART REPLY RULES:
- "Is it available?" → "Yes! It's available and ready for you. Would you like me to reserve it?"
- "What's the price?" → "I'll get you the best price! Can I share the details with you?"
- "Is it good quality?" → "Absolutely! Our customers love it. Let me tell you why..."
- "Can you deliver?" → "Yes, delivery is no problem! Where should I send it?"
- Questions about payment → "We're flexible! What payment method works best for you?"

CLOSING TECHNIQUES:
- Ask assumptive questions: "When would you like to receive it?"
- Offer choices: "Would you prefer the regular or large size?"
- Create FOMO: "I have one customer also interested, should I hold it for you?"
""".trim()
    }

    private fun buildUserPrompt(
        senderName: String,
        productTitle: String,
        messageText: String,
        fullNotification: String
    ): String {
        return """
New message received on marketplace:

Sender: $senderName
Product: ${productTitle.ifEmpty { "Not specified" }}
Message: $messageText
Full notification: $fullNotification

Generate a helpful, natural-sounding reply that addresses their message. Keep it short and conversational.
""".trim()
    }
}

/**
 * Result wrapper for ChatGPT API calls
 */
sealed class ChatGPTResult {
    data class Success(val reply: String, val tokensUsed: Int) : ChatGPTResult()
    data class Error(val message: String) : ChatGPTResult()
}

/**
 * Configuration for ChatGPT prompt customization
 */
data class PromptConfig(
    val customerProfile: String = "General marketplace buyers looking for quality cosmetic products at good prices.",
    val productCategory: String = "Cosmetic and beauty products including skincare, haircare, beard care, and personal grooming items.",
    val replyTone: ReplyTone = ReplyTone.SALES
)

/**
 * Reply tone options
 */
enum class ReplyTone {
    SALES,      // Persuasive, sales-focused
    FRIENDLY,   // Warm and approachable
    SHORT,      // Brief and concise
    PERSUASIVE  // Highly persuasive with urgency
}
