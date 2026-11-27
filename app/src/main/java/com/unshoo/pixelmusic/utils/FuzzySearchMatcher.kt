package com.unshoo.pixelmusic.utils

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Typo-tolerant, diacritic-insensitive matcher for local library search fallback.
 * Used when strict SQLite FTS4/LIKE queries miss misspellings, transposed letters,
 * missing apostrophes, or out-of-order cross-field queries (e.g. "arjit tum hi ho").
 */
object FuzzySearchMatcher {

    private val COMBINING_MARKS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val APOSTROPHE_REGEX = Regex("['’`]")
    private val NON_ALNUM_REGEX = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(input: String): String {
        if (input.isBlank()) return ""
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        val withoutMarks = COMBINING_MARKS_REGEX.replace(decomposed, "")
        val withoutApostrophes = APOSTROPHE_REGEX.replace(withoutMarks, "")
        return NON_ALNUM_REGEX.replace(withoutApostrophes, " ")
            .lowercase(Locale.ROOT)
            .trim()
    }

    fun tokenize(normalized: String): List<String> =
        if (normalized.isBlank()) emptyList()
        else normalized.split(' ').filter { it.isNotEmpty() }

    /**
     * Optimal String Alignment (restricted Damerau-Levenshtein) distance with early exit
     * when the distance exceeds [maxDistance].
     */
    fun damerauLevenshtein(a: String, b: String, maxDistance: Int): Int {
        if (a == b) return 0
        if (maxDistance <= 0) return maxDistance + 1
        val lenA = a.length
        val lenB = b.length
        if (abs(lenA - lenB) > maxDistance) return maxDistance + 1
        if (lenA == 0) return lenB
        if (lenB == 0) return lenA

        var prevPrev = IntArray(lenB + 1)
        var prev = IntArray(lenB + 1) { it }
        var curr = IntArray(lenB + 1)

        for (i in 1..lenA) {
            curr[0] = i
            var rowMin = curr[0]
            val ai = a[i - 1]
            for (j in 1..lenB) {
                val bj = b[j - 1]
                val cost = if (ai == bj) 0 else 1
                var value = min(
                    min(prev[j] + 1, curr[j - 1] + 1),
                    prev[j - 1] + cost
                )
                if (i > 1 && j > 1 && ai == b[j - 2] && a[i - 2] == bj) {
                    value = min(value, prevPrev[j - 2] + 1)
                }
                curr[j] = value
                if (value < rowMin) rowMin = value
            }
            if (rowMin > maxDistance) return maxDistance + 1
            val tmp = prevPrev
            prevPrev = prev
            prev = curr
            curr = tmp
        }
        return prev[lenB]
    }

    private fun maxAllowedDistance(tokenLength: Int): Int = when {
        tokenLength <= 2 -> 0
        tokenLength <= 5 -> 1
        tokenLength <= 9 -> 2
        else -> 3
    }

    private fun scoreField(
        queryNorm: String,
        queryTokens: List<String>,
        queryCompact: String,
        field: String?,
    ): Int {
        if (field.isNullOrBlank() || queryNorm.isEmpty()) return 0
        val fieldNorm = normalize(field)
        if (fieldNorm.isEmpty()) return 0

        if (fieldNorm == queryNorm) return 1000
        if (fieldNorm.startsWith(queryNorm)) return 850
        if (queryNorm.length >= 2 && fieldNorm.contains(queryNorm)) return 650

        if (queryCompact.length >= 3) {
            val fieldCompact = fieldNorm.replace(" ", "")
            if (fieldCompact == queryCompact) return 900
            if (fieldCompact.contains(queryCompact)) return 600
        }

        if (queryTokens.isEmpty()) return 0
        val fieldTokens = tokenize(fieldNorm)
        if (fieldTokens.isEmpty()) return 0

        var matchedCount = 0
        var totalTokenScore = 0
        for (qt in queryTokens) {
            var bestForToken = 0
            for (ft in fieldTokens) {
                if (ft == qt) {
                    bestForToken = max(bestForToken, 120)
                    break
                }
                if (ft.startsWith(qt)) {
                    bestForToken = max(bestForToken, 95)
                    continue
                }
                if (qt.length >= 3 && qt.startsWith(ft) && ft.length >= 3) {
                    bestForToken = max(bestForToken, 75)
                    continue
                }
                val limit = maxAllowedDistance(max(qt.length, ft.length))
                if (limit > 0) {
                    val dist = damerauLevenshtein(qt, ft, limit)
                    if (dist <= limit) {
                        val fuzzyScore = (85 - dist * 22).coerceAtLeast(25)
                        bestForToken = max(bestForToken, fuzzyScore)
                    }
                }
            }
            if (bestForToken > 0) {
                matchedCount++
                totalTokenScore += bestForToken
            }
        }

        val requiredMatches = when (queryTokens.size) {
            1 -> 1
            2 -> 2
            else -> (queryTokens.size * 2 + 2) / 3
        }
        if (matchedCount < requiredMatches) return 0

        val coverageBonus = if (matchedCount == queryTokens.size) 100 else 0
        return (totalTokenScore / queryTokens.size) + coverageBonus
    }

    /**
     * Scores a candidate item across weighted fields (e.g. `title to 3`, `artist to 2`, `album to 1`)
     * as well as the combined text across all fields for multi-field queries. Returns 0 when unmatched.
     */
    fun scoreFields(query: String, vararg weightedFields: Pair<String?, Int>): Int {
        val queryNorm = normalize(query)
        if (queryNorm.isEmpty()) return 0
        val queryTokens = tokenize(queryNorm)
        val queryCompact = queryNorm.replace(" ", "")

        var bestWeightedScore = 0
        for ((field, weight) in weightedFields) {
            val base = scoreField(queryNorm, queryTokens, queryCompact, field)
            if (base > 0) {
                bestWeightedScore = max(bestWeightedScore, base * weight)
            }
        }

        if (weightedFields.size > 1) {
            val combined = weightedFields.mapNotNull { it.first?.takeIf(String::isNotBlank) }.joinToString(" ")
            val combinedScore = scoreField(queryNorm, queryTokens, queryCompact, combined)
            if (combinedScore > 0) {
                bestWeightedScore = max(bestWeightedScore, combinedScore)
            }
        }

        return bestWeightedScore
    }
}
