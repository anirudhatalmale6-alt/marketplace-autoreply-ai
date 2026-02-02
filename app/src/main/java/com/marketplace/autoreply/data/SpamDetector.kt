package com.marketplace.autoreply.data

/**
 * Spam Detection System
 * Detects spam messages to prevent sending ChatGPT requests and avoid wasting API calls.
 * Marks suspicious messages as spam and logs them accordingly.
 */
class SpamDetector {

    companion object {
        private const val TAG = "SpamDetector"

        // Emoji detection pattern (covers most common emojis)
        private val EMOJI_PATTERN = Regex("[\\x{1F600}-\\x{1F64F}\\x{1F300}-\\x{1F5FF}\\x{1F680}-\\x{1F6FF}\\x{1F1E0}-\\x{1F1FF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}\\x{FE00}-\\x{FE0F}\\x{1F900}-\\x{1F9FF}]", RegexOption.IGNORE_CASE)

        // Threshold for excessive emojis
        private const val EMOJI_THRESHOLD = 15

        // Threshold for repetitive characters
        private const val REPETITIVE_CHAR_THRESHOLD = 5

        // Minimum message length for meaningful content
        private const val MIN_MEANINGFUL_LENGTH = 2

        // Common spam patterns
        private val SPAM_PATTERNS = listOf(
            Regex("(..)\\1{4,}", RegexOption.IGNORE_CASE),  // Repeated 2-char sequences 5+ times
            Regex("(.)\\1{9,}"),  // Same character repeated 10+ times
            Regex("^[\\s\\p{P}]+$"),  // Only punctuation/whitespace
            Regex("^[0-9\\s]+$"),  // Only numbers and spaces
        )

        // Spam keywords (in multiple languages)
        private val SPAM_KEYWORDS = listOf(
            "free money", "click here", "you won", "lottery",
            "make money fast", "work from home easy", "guaranteed income",
            "urgent response needed", "claim your prize"
        )
    }

    /**
     * Analyze a message for spam characteristics
     * @param messageText The message to analyze
     * @return SpamAnalysisResult with detection details
     */
    fun analyzeMessage(messageText: String): SpamAnalysisResult {
        val reasons = mutableListOf<String>()
        var spamScore = 0

        // Check for empty or very short messages
        val cleanedText = messageText.trim()
        if (cleanedText.length < MIN_MEANINGFUL_LENGTH) {
            reasons.add("Message too short")
            spamScore += 30
        }

        // Count emojis
        val emojiCount = countEmojis(cleanedText)
        if (emojiCount > EMOJI_THRESHOLD) {
            reasons.add("Excessive emojis ($emojiCount)")
            spamScore += 40
        } else if (emojiCount > EMOJI_THRESHOLD / 2) {
            spamScore += 15  // Partial penalty for many emojis
        }

        // Check emoji-to-text ratio (if message is mostly emojis)
        val textWithoutEmojis = EMOJI_PATTERN.replace(cleanedText, "").trim()
        if (emojiCount > 5 && textWithoutEmojis.length < 10) {
            reasons.add("Message is mostly emojis")
            spamScore += 30
        }

        // Check for repetitive patterns
        for (pattern in SPAM_PATTERNS) {
            if (pattern.containsMatchIn(cleanedText)) {
                reasons.add("Repetitive pattern detected")
                spamScore += 25
                break
            }
        }

        // Check for repetitive characters
        if (hasExcessiveRepetition(cleanedText)) {
            reasons.add("Excessive character repetition")
            spamScore += 20
        }

        // Check for spam keywords
        val lowerText = cleanedText.lowercase()
        for (keyword in SPAM_KEYWORDS) {
            if (lowerText.contains(keyword)) {
                reasons.add("Spam keyword: $keyword")
                spamScore += 35
                break
            }
        }

        // Check for all caps (shouting)
        if (cleanedText.length > 10) {
            val upperCount = cleanedText.count { it.isUpperCase() }
            val letterCount = cleanedText.count { it.isLetter() }
            if (letterCount > 0 && upperCount.toDouble() / letterCount > 0.8) {
                reasons.add("Excessive capitals")
                spamScore += 15
            }
        }

        // Check for random/gibberish text (no vowels in long text)
        if (cleanedText.length > 15) {
            val vowelCount = cleanedText.lowercase().count { it in "aeiouéèêëàâäùûüôöîïAEIOUÉÈÊËÀÂÄÙÛÜÔÖÎÏ" }
            if (vowelCount == 0) {
                reasons.add("No vowels (possible gibberish)")
                spamScore += 25
            }
        }

        val isSpam = spamScore >= 50

        if (isSpam) {
            AppLogger.warn(TAG, "SPAM detected (score: $spamScore): ${reasons.joinToString(", ")}")
        }

        return SpamAnalysisResult(
            isSpam = isSpam,
            spamScore = spamScore,
            reasons = reasons,
            originalMessage = cleanedText
        )
    }

    private fun countEmojis(text: String): Int {
        return EMOJI_PATTERN.findAll(text).count()
    }

    private fun hasExcessiveRepetition(text: String): Boolean {
        if (text.length < 5) return false

        var maxRepeat = 1
        var currentRepeat = 1
        var prevChar: Char? = null

        for (char in text) {
            if (char == prevChar) {
                currentRepeat++
                maxRepeat = maxOf(maxRepeat, currentRepeat)
            } else {
                currentRepeat = 1
            }
            prevChar = char
        }

        return maxRepeat >= REPETITIVE_CHAR_THRESHOLD
    }
}

/**
 * Result of spam analysis
 */
data class SpamAnalysisResult(
    val isSpam: Boolean,
    val spamScore: Int,  // 0-100, >= 50 is considered spam
    val reasons: List<String>,
    val originalMessage: String
)
