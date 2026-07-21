package com.riddleapp.diary

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Tolerant text comparison for handwriting. Recognition of real cursive is rarely exact — a stroke
 * joins two letters, a dot goes missing — so recall is matched on closeness rather than equality.
 */
object TextMatch {

    private val NOT_LETTERS = Regex("""[^a-z0-9 ]""")
    private val SPACES = Regex("""\s+""")

    /** Lowercase, strip punctuation, collapse spaces — the form everything is compared in. */
    fun normalize(text: String): String =
        text.lowercase(Locale.US).replace(NOT_LETTERS, " ").replace(SPACES, " ").trim()

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    /** 1.0 is identical, 0.0 is nothing alike. */
    fun similarity(a: String, b: String): Double {
        val longest = max(a.length, b.length)
        if (longest == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / longest
    }

    /** True when [text] starts with something close enough to [prefix]; scoped to that prefix length. */
    fun startsWithApprox(text: String, prefix: String, threshold: Double = 0.75): Boolean {
        if (text.length < prefix.length / 2) return false
        val window = text.take(min(text.length, prefix.length + PREFIX_SLACK))
        // Try trimming the window back so a slightly longer reading still lines up with the prefix.
        for (end in window.length downTo max(1, prefix.length - PREFIX_SLACK)) {
            if (similarity(window.take(end), prefix) >= threshold) return true
        }
        return false
    }

    /** Whatever follows an approximate [prefix] — the subject of a search. */
    fun afterApprox(text: String, prefix: String, threshold: Double = 0.75): String? {
        if (!startsWithApprox(text, prefix, threshold)) return null
        var best: String? = null
        var bestScore = threshold
        for (end in min(text.length, prefix.length + PREFIX_SLACK) downTo max(1, prefix.length - PREFIX_SLACK)) {
            val score = similarity(text.take(end), prefix)
            if (score >= bestScore) {
                bestScore = score
                best = text.drop(end).trim()
            }
        }
        return best?.takeIf { it.isNotEmpty() }
    }

    /**
     * Nudges each word toward one the diary has actually seen before, so a misread "gardn" still
     * finds "garden". Only replaces a word when a close match exists — unknown words are left alone.
     */
    fun correctAgainst(text: String, vocabulary: Set<String>): String {
        if (vocabulary.isEmpty()) return text
        return text.split(" ").joinToString(" ") { word ->
            if (word.length < MIN_CORRECTABLE || vocabulary.contains(word)) return@joinToString word
            val candidate = vocabulary
                .filter { kotlin.math.abs(it.length - word.length) <= MAX_LENGTH_DRIFT }
                .minByOrNull { levenshtein(word, it) }
                ?: return@joinToString word
            if (levenshtein(word, candidate) <= maxEdits(word)) candidate else word
        }
    }

    private fun maxEdits(word: String): Int = when {
        word.length <= 4 -> 1
        word.length <= 8 -> 2
        else -> 3
    }

    private const val PREFIX_SLACK = 4
    private const val MIN_CORRECTABLE = 3
    private const val MAX_LENGTH_DRIFT = 3
}
