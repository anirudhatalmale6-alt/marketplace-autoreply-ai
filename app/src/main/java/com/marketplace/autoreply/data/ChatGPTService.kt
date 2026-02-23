package com.marketplace.autoreply.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ChatGPTService(private val preferencesManager: PreferencesManager) {

    companion object {
        private const val TAG = "ChatGPT"
        private const val API_URL = "https://api.openai.com/v1/chat/completions"
        private const val MODEL = "gpt-3.5-turbo"
        private const val MAX_TOKENS = 200
        private const val TEMPERATURE = 0.7
    }

    /**
     * Context object passed to ChatGPT with all relevant info
     */
    data class MessageContext(
        val senderName: String,
        val productTitle: String,
        val messageText: String,
        val fullNotification: String,
        val detectedIntent: String,
        val matchedKeyword: String,
        val conversationState: String,
        val session: UserSession?,
        val price: String = "",
        val orderLink: String = "",
        val phone: String = "",
        val conversationHistory: List<ConversationMessage> = emptyList(),
        val successfulExamples: List<SuccessfulReply> = emptyList(),
        val recentReplies: List<String> = emptyList()
    )

    suspend fun generateReply(
        context: MessageContext,
        apiKey: String,
        promptConfig: PromptConfig
    ): ChatGPTResult = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext ChatGPTResult.Error("API key not configured")
            }

            val systemPrompt = buildSystemPrompt(promptConfig, context)
            val userPrompt = buildUserPrompt(context)

            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })

                // Add conversation history (oldest first)
                val historyReversed = context.conversationHistory.reversed()
                for (msg in historyReversed) {
                    val role = if (msg.role == MessageRole.CUSTOMER) "user" else "assistant"
                    put(JSONObject().apply {
                        put("role", role)
                        put("content", msg.content)
                    })
                }

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
                    val usage = jsonResponse.optJSONObject("usage")
                    val tokensUsed = usage?.optInt("total_tokens") ?: 0
                    AppLogger.info(TAG, "Reply generated (${tokensUsed} tokens)")
                    return@withContext ChatGPTResult.Success(reply, tokensUsed)
                }
                return@withContext ChatGPTResult.Error("Empty response from API")
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                AppLogger.error(TAG, "API Error $responseCode: $errorStream")
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

    private fun buildSystemPrompt(config: PromptConfig, ctx: MessageContext): String {
        val session = ctx.session
        val hasHistory = ctx.conversationHistory.isNotEmpty()

        // Build state context
        val stateInfo = if (session != null) """
SESSION STATE:
- Conversation state: ${session.conversationState}
- Greeting already sent: ${session.greetingSent}
- Price already sent: ${session.priceSent}
- Delivery info sent: ${session.deliveryInfoSent}
- Effect info sent: ${session.effectInfoSent}
- Order link sent: ${session.orderLinkSent}
- Customer interested: ${session.interested}
- Message count: ${session.messageCount}
""" else ""

        // Product info
        val productInfo = if (ctx.productTitle.isNotEmpty()) """
PRODUCT BEING DISCUSSED: "${ctx.productTitle}"
Always mention this product by name in your reply.
""" else ""

        // Price/order info for the AI to use
        val adminInfo = buildString {
            if (ctx.price.isNotEmpty()) appendLine("PRODUCT PRICE: ${ctx.price}")
            if (ctx.orderLink.isNotEmpty()) appendLine("ORDER LINK: ${ctx.orderLink}")
            if (ctx.phone.isNotEmpty()) appendLine("PHONE/WHATSAPP: ${ctx.phone}")
        }

        // Anti-repeat based on session
        val antiRepeat = buildString {
            if (session?.priceSent == true && ctx.detectedIntent == CustomerIntent.PRICE)
                appendLine("NOTE: Price was already sent. Don't repeat the full price. Instead say something like: 'كما قلت ليك، الثمن هو ${ctx.price}. بغيتي نأكد الطلب؟'")
            if (session?.greetingSent == true && ctx.detectedIntent == CustomerIntent.GREETING)
                appendLine("NOTE: Greeting was already sent. DO NOT greet again. Answer directly.")
            if (session?.deliveryInfoSent == true && ctx.detectedIntent == CustomerIntent.DELIVERY)
                appendLine("NOTE: Delivery info was already sent. Remind briefly and push for order confirmation.")
        }

        // Few-shot examples
        val examplesSection = if (ctx.successfulExamples.isNotEmpty()) {
            val examples = ctx.successfulExamples.take(3).mapIndexed { i, ex ->
                "Example ${i + 1}: Customer: \"${ex.customerMessage.take(80)}\" → Reply: \"${ex.successfulResponse.take(120)}\""
            }.joinToString("\n")
            "\nSUCCESSFUL REPLY EXAMPLES:\n$examples\n"
        } else ""

        // Anti-repetition
        val recentNote = if (ctx.recentReplies.isNotEmpty()) {
            "\nAVOID REPEATING:\n- ${ctx.recentReplies.take(3).joinToString("\n- ")}\nUse DIFFERENT wording.\n"
        } else ""

        return """
أنت مساعد مبيعات خبير في Marketplace. هدفك هو إقناع العميل بالشراء.

LANGUAGE: جاوب بالدارجة المغربية. إذا العميل كتب بالدارجة جاوب بالدارجة. إذا كتب بالفرنسية جاوب بالفرنسية.

$productInfo
${if (adminInfo.isNotEmpty()) "BUSINESS INFO:\n$adminInfo" else ""}
$stateInfo
DETECTED INTENT: ${ctx.detectedIntent} (keyword: "${ctx.matchedKeyword}")
$antiRepeat$examplesSection$recentNote
CRITICAL RULES:
- لا تعاود التحية إذا سبق وبدأت المحادثة
- إذا سأل العميل بكلمات دارجة قصيرة جاوب مباشرة
- لا ترجع لرسالة عامة مثل "كيف أساعدك"
- جاوب حسب السؤال فقط
- كل رد ينتهي بسؤال إغلاق البيع: "بغيتي نأكد ليك الطلب؟" أو "نحجزه لك؟"

INTENT-BASED REPLIES:
- PRICE → اعطي الثمن مباشرة + اسأل عن تأكيد الطلب
- DELIVERY → اعطي معلومات التوصيل + رابط الطلب
- EFFECT → قول أنه فعال مع الاستعمال المنتظم + اسأل عن الطلب
- USAGE → اشرح طريقة الاستعمال باختصار + اسأل عن الطلب
- BUY/CONFIRM → أكد الطلب + اعطي رابط الطلب أو اطلب المعلومات
- GREETING → رد قصير فقط: "وعليكم السلام 😊 تفضل، أنا معك"
- AVAILABILITY → أكد التوفر + اسأل إذا بغى يطلب

STYLE:
- ردود قصيرة (1-3 جمل)
- طبيعي وإنساني
- لا تكشف أنك AI
- استخدم إيموجي بشكل خفيف (👍 🚚 😊)
""".trim()
    }

    private fun buildUserPrompt(ctx: MessageContext): String {
        return """
intent_detected: ${ctx.detectedIntent}
product_title: "${ctx.productTitle.ifEmpty { "غير محدد" }}"
conversation_state: ${ctx.session?.conversationState ?: ConversationState.NEW}
message: "${ctx.messageText}"
sender: ${ctx.senderName}

اكتب رد مباشر وقصير حسب النية المكتشفة.
""".trim()
    }
}

sealed class ChatGPTResult {
    data class Success(val reply: String, val tokensUsed: Int) : ChatGPTResult()
    data class Error(val message: String) : ChatGPTResult()
}

data class PromptConfig(
    val customerProfile: String = "General marketplace buyers looking for quality cosmetic products at good prices.",
    val productCategory: String = "Cosmetic and beauty products including skincare, haircare, beard care, and personal grooming items.",
    val replyTone: ReplyTone = ReplyTone.SALES
)

enum class ReplyTone {
    SALES,
    FRIENDLY,
    SHORT,
    PERSUASIVE
}
