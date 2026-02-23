package com.marketplace.autoreply.data

class DarijaIntentDetector {

    data class DetectedIntent(
        val intent: String,
        val confidence: Float,
        val matchedKeyword: String
    )

    fun detectIntent(message: String): DetectedIntent {
        val lowerMessage = message.lowercase().trim()

        for (keyword in BUY_KEYWORDS) {
            if (lowerMessage.contains(keyword))
                return DetectedIntent(CustomerIntent.BUY, 0.9f, keyword)
        }
        for (keyword in CONFIRM_KEYWORDS) {
            if (lowerMessage.contains(keyword))
                return DetectedIntent(CustomerIntent.CONFIRM, 0.9f, keyword)
        }
        for (keyword in PRICE_KEYWORDS) {
            if (lowerMessage.contains(keyword))
                return DetectedIntent(CustomerIntent.PRICE, 0.85f, keyword)
        }
        for (keyword in DELIVERY_KEYWORDS) {
            if (lowerMessage.contains(keyword))
                return DetectedIntent(CustomerIntent.DELIVERY, 0.85f, keyword)
        }
        for (keyword in EFFECT_KEYWORDS) {
            if (lowerMessage.contains(keyword))
                return DetectedIntent(CustomerIntent.EFFECT, 0.85f, keyword)
        }
        for (keyword in USAGE_KEYWORDS) {
            if (lowerMessage.contains(keyword))
                return DetectedIntent(CustomerIntent.USAGE, 0.85f, keyword)
        }
        for (keyword in AVAILABILITY_KEYWORDS) {
            if (lowerMessage.contains(keyword))
                return DetectedIntent(CustomerIntent.AVAILABILITY, 0.8f, keyword)
        }
        for (keyword in GREETING_KEYWORDS) {
            if (lowerMessage.contains(keyword))
                return DetectedIntent(CustomerIntent.GREETING, 0.7f, keyword)
        }
        return DetectedIntent(CustomerIntent.UNKNOWN, 0.3f, "")
    }

    private val PRICE_KEYWORDS = listOf(
        "tmn", "thmn", "chhal", "bchhal", "bchhl", "ch7al", "taman",
        "ثمن", "بشحال", "شحال",
        "prix", "combien", "tarif", "cout", "coût",
        "كم", "سعر", "الثمن", "بكم", "كم الثمن",
        "price", "cost", "how much"
    )
    private val DELIVERY_KEYWORDS = listOf(
        "tawsil", "twsil", "liwrazon", "tawssil", "توصيل",
        "livraison", "livrer", "envoyer",
        "توصيل", "شحن", "ارسال", "إرسال",
        "delivery", "deliver", "shipping", "send"
    )
    private val EFFECT_KEYWORDS = listOf(
        "fa3al", "fa3ala", "kaykhdam", "khdama", "natija", "nataij",
        "فعال", "كيخدم", "خدامة", "نتيجة", "نتائج",
        "wach fa3al", "wach kaykhdam", "kayna natija",
        "efficace", "resultat", "résultat", "marche",
        "effective", "result", "does it work"
    )
    private val USAGE_KEYWORDS = listOf(
        "kifach", "kif", "ki ndirha", "tari9a", "ista3mal",
        "كيفاش", "كيف", "طريقة", "استعمال",
        "comment", "utiliser", "utilisation",
        "how to use", "usage", "instructions"
    )
    private val BUY_KEYWORDS = listOf(
        "bghit", "brhit", "nachri", "nchri", "nakhod", "nakhd",
        "بغيت", "نشري", "ناخد",
        "acheter", "commander", "je veux",
        "اشتري", "أريد", "اطلب",
        "buy", "order", "i want", "purchase"
    )
    private val CONFIRM_KEYWORDS = listOf(
        "wakha", "wkha", "ok", "safi", "akhay",
        "واخا", "صافي", "أكيد", "نعم",
        "oui", "daccord", "d'accord", "parfait",
        "نعم", "أكيد", "موافق", "تمام",
        "yes", "sure", "confirm", "okay", "alright", "deal"
    )
    private val AVAILABILITY_KEYWORDS = listOf(
        "kayn", "kayna", "mazal", "disponible",
        "كاين", "كاينة", "مازال",
        "disponible", "en stock", "dispo",
        "متوفر", "موجود", "متاح",
        "available", "in stock"
    )
    private val GREETING_KEYWORDS = listOf(
        "salam", "slm", "slam", "mrhba", "ahlan",
        "سلام", "مرحبا", "أهلا", "السلام عليكم",
        "bonjour", "bonsoir", "salut",
        "hello", "hi", "hey"
    )
}

object CustomerIntent {
    const val GREETING = "GREETING"
    const val PRICE = "PRICE"
    const val DELIVERY = "DELIVERY"
    const val EFFECT = "EFFECT"
    const val USAGE = "USAGE"
    const val BUY = "BUY"
    const val CONFIRM = "CONFIRM"
    const val AVAILABILITY = "AVAILABILITY"
    const val UNKNOWN = "UNKNOWN"
}
