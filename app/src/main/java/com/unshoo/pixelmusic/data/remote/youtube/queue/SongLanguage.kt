package com.unshoo.pixelmusic.data.remote.youtube.queue

import java.util.Locale

/**
 * Cheap on-device language/vibe guess for a song from its title, artist and album.
 * No network, no ML: Unicode script ranges first, then small marker-word lists for
 * romanized Indian languages (most Hindi/Punjabi/Haryanvi titles are written in Latin
 * letters) and Brazilian funk. Returns "" when unsure, so callers never drop a song
 * just because its language can't be told.
 *
 * Used by SmartQueue to keep autoplay in the language of the song the user started with.
 */
object SongLanguage {

    const val UNKNOWN = ""

    fun guess(title: String?, artist: String?, album: String?): String {
        val text = listOfNotNull(title, album, artist).joinToString(" ")
        if (text.isBlank()) return UNKNOWN
        script(text)?.let { return it }

        val words = text.lowercase(Locale.ROOT).split(NON_LETTER).filter { it.length > 1 }.toSet()
        if (words.isEmpty()) return UNKNOWN
        val hr = words.count { it in HARYANVI }
        val pa = words.count { it in PUNJABI }
        val hi = words.count { it in HINDI }
        val pt = words.count { it in BR_FUNK }
        val en = words.count { it in ENGLISH }
        return when {
            hr > 0 -> "hr"
            pt > 0 && pt >= hi + pa -> "pt"
            pa > 0 && pa >= hi -> "pa"
            hi > 0 -> "hi"
            en > 0 -> "en"
            else -> UNKNOWN
        }
    }

    /** Same language, or either side unknown (unknown never blocks). */
    fun compatible(a: String, b: String): Boolean = a.isEmpty() || b.isEmpty() || a == b

    /** Language from the writing system, when the text uses a non-Latin script. */
    private fun script(text: String): String? {
        var counts: IntArray? = null
        for (ch in text) {
            val bucket = when (ch.code) {
                in 0x0900..0x097F -> 0 // Devanagari: Hindi / Haryanvi / Bhojpuri
                in 0x0A00..0x0A7F -> 1 // Gurmukhi: Punjabi
                in 0x0600..0x06FF -> 2 // Arabic script: Urdu / Arabic / Persian
                in 0x0B80..0x0BFF -> 3 // Tamil
                in 0x0C00..0x0C7F -> 4 // Telugu
                in 0x0C80..0x0CFF -> 5 // Kannada
                in 0x0D00..0x0D7F -> 6 // Malayalam
                in 0x0980..0x09FF -> 7 // Bengali
                in 0xAC00..0xD7AF, in 0x1100..0x11FF -> 8 // Korean
                in 0x3040..0x30FF -> 9 // Japanese kana
                in 0x0400..0x04FF -> 10 // Cyrillic
                else -> -1
            }
            if (bucket >= 0) {
                val c = counts ?: IntArray(SCRIPT_CODES.size).also { counts = it }
                c[bucket]++
            }
        }
        val c = counts ?: return null
        val best = c.indices.maxByOrNull { c[it] } ?: return null
        return if (c[best] >= 2) SCRIPT_CODES[best] else null
    }

    private val SCRIPT_CODES = arrayOf("hi", "pa", "ur", "ta", "te", "kn", "ml", "bn", "ko", "ja", "ru")
    private val NON_LETTER = Regex("[^\\p{L}]+")

    private val HARYANVI = setOf(
        "haryanvi", "hariyanvi", "haryana", "chhora", "chhori", "chora", "chori", "jaat", "jatni",
        "rohtak", "nanad", "bhabhi", "jethani", "lath", "laadli", "chhoriyan", "kharkiya",
    )
    private val PUNJABI = setOf(
        "jatt", "jatti", "jattan", "kudi", "kudiye", "kuriye", "munda", "gabru", "sohneya", "sohniye",
        "mainu", "tainu", "menu", "tenu", "putt", "pind", "sardar", "sardaar", "punjabi", "punjab",
        "yaaran", "mutiyaar", "jind", "kithe", "jandi", "rehnda", "bandook", "velly", "akhan", "akhiyan",
    )
    private val HINDI = setOf(
        "dil", "pyaar", "pyar", "ishq", "mohabbat", "tere", "mere", "tera", "mera", "teri", "meri",
        "tujhe", "mujhe", "tum", "hum", "sanam", "sajna", "yaara", "kya", "hai", "hain", "hoon",
        "nahi", "nahin", "zindagi", "raat", "chand", "jaan", "bewafa", "kaise", "kyun", "judaai",
        "duniya", "rabba", "dhadkan", "aankhein", "aashiqui", "khuda", "saathiya", "jaana", "ladki",
        "kabhi", "phir", "tujhse", "mujhse", "hindi", "bollywood", "mahiya", "baarish", "sapna",
        "humsafar", "pehla", "tujh", "kaun", "yeh", "woh", "aaj", "kal", "naina", "piya", "saiyaan",
    )
    private val BR_FUNK = setOf(
        "montagem", "automotivo", "bailao", "bailão", "mandelao", "mandelão", "tropa", "novinha",
        "sentando", "voce", "você", "nao", "não", "minha", "meu", "funk", "brasileiro", "mtg",
    )
    private val ENGLISH = setOf(
        "the", "you", "your", "love", "me", "my", "and", "of", "to", "in", "on", "for", "with", "is",
        "it", "be", "we", "us", "all", "not", "don't", "dont", "can't", "cant", "won't", "never",
        "ever", "now", "time", "night", "day", "life", "world", "heart", "baby", "girl", "boy",
        "feel", "feeling", "dance", "light", "lights", "fire", "stay", "go", "come", "back", "down",
        "up", "out", "home", "dream", "dreams", "say", "tell", "know", "want", "need", "one", "good",
        "bad", "way", "without", "forever", "again", "last", "alone", "lonely", "crazy", "sweet",
        "blue", "rain", "sun", "moon", "star", "stars", "sky", "run", "fall", "high", "like", "just",
        "little", "only", "more", "better", "together", "somebody", "someone", "nobody", "everything",
        "nothing", "dark", "party", "lovely", "what", "when", "where", "who", "why", "this", "that",
    )
}
