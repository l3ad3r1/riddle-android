package com.riddleapp.diary

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recognises the two things riddle lets you ask the page for, and answers them from what is already
 * on the tablet. Because the pen's writing is transcribed on device, a recall is matched locally —
 * no request, no waiting, and it still works with no network at all.
 */
object Recall {

    sealed interface Intent {
        /** "what do you remember?" — a handwritten index of remembered pages. */
        data object Index : Intent

        /** "show me what I wrote about the garden" — conjure one page back. */
        data class Search(val query: String) : Intent

        /** A large "?" alone on the page — the diary explains itself. */
        data object Guide : Intent
    }

    /** Phrases meaning "list what you remember", compared approximately. */
    private val indexPhrases = listOf(
        "what do you remember",
        "what do you recall",
        "what have you remembered",
        "what can you remember",
    )

    /** Openings that introduce a subject; whatever follows one is the thing to look for. */
    private val searchPrefixes = listOf(
        "show me what i wrote about",
        "show me the page about",
        "show me what i wrote on",
        "what did i write about",
        "find what i wrote about",
        "find the page about",
        "remember when i wrote about",
        "show me about",
    )

    fun parse(transcript: String?): Intent? = parse(listOfNotNull(transcript))

    /**
     * Recognition returns several readings of the same page; any of them meaning a recall is enough,
     * so all are tried before giving up. Matching is approximate — handwriting is rarely read exactly.
     */
    fun parse(candidates: List<String>): Intent? {
        // Checked before normalizing, which strips punctuation and would erase a lone "?".
        if (candidates.any { QUESTION_MARK.matches(it.trim()) }) return Intent.Guide

        val texts = candidates.mapNotNull { TextMatch.normalize(it).takeIf(String::isNotEmpty) }
        if (texts.isEmpty()) return null

        for (text in texts) {
            if (indexPhrases.any { TextMatch.similarity(text, it) >= INDEX_THRESHOLD }) return Intent.Index
        }
        for (text in texts) {
            for (prefix in searchPrefixes) {
                val subject = TextMatch.afterApprox(text, prefix, PREFIX_THRESHOLD)
                if (!subject.isNullOrEmpty()) return Intent.Search(subject)
            }
        }
        return null
    }

    /** Every word the diary has seen, for nudging a misread search term toward a real one. */
    fun vocabulary(pages: List<MemoryStore.PastPage>): Set<String> =
        pages.flatMap { page ->
            TextMatch.normalize(page.transcript + " " + page.reply).split(" ")
        }.filter { it.length > 2 }.toSet()

    /** A page holding nothing but question marks — recognition often reads a big "?" as "??". */
    private val QUESTION_MARK = Regex("""\?{1,3}""")

    private const val INDEX_THRESHOLD = 0.72
    private const val PREFIX_THRESHOLD = 0.72

    /**
     * Best match for [query] among remembered pages, scored on how many query words appear in the
     * page's own words. Ties go to the more recent page, since [pages] arrives newest first.
     */
    fun search(pages: List<MemoryStore.PastPage>, query: String): MemoryStore.PastPage? {
        // Nudge the search words toward ones the diary has actually written before recognising a
        // misreading as a genuine miss.
        val corrected = TextMatch.correctAgainst(TextMatch.normalize(query), vocabulary(pages))
        // Common words appear on nearly every page, so matching one is not evidence of anything —
        // without this, asking for a subject you never wrote about returns an arbitrary page.
        val terms = corrected.split(WHITESPACE)
            .filter { it.length > 2 && it !in STOPWORDS }
        if (terms.isEmpty()) return null
        return pages
            .map { page -> page to score(page, terms) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun score(page: MemoryStore.PastPage, terms: List<String>): Int {
        val haystack = (page.transcript + " " + page.reply).lowercase(Locale.US)
        return terms.count { haystack.contains(it) }
    }

    /** One line per remembered page: the date, and what the pen said that day. */
    fun indexText(pages: List<MemoryStore.PastPage>, limit: Int = 8): String {
        if (pages.isEmpty()) return "The pages are still blank. I remember nothing yet."
        val header = "I remember ${pages.size} " + if (pages.size == 1) "page." else "pages."
        // One entry per line — as a single paragraph the dates run together unreadably.
        return pages.take(limit).joinToString("\n", prefix = "$header\n") { page ->
            "${formatDate(page.id)} - ${summarize(page)}"
        }
    }

    fun formatDate(id: Long): String =
        SimpleDateFormat("d MMM", Locale.US).format(Date(id))

    /**
     * Prefer what the pen wrote, but a one- or two-character transcript is a misread scribble rather
     * than a memory — the diary's own reply describes such a page far better.
     */
    private fun summarize(page: MemoryStore.PastPage): String {
        val source = page.transcript.takeIf { it.trim().length >= MIN_USEFUL_TRANSCRIPT } ?: page.reply
        return if (source.length <= SUMMARY_CHARS) source else source.take(SUMMARY_CHARS).trimEnd() + "..."
    }

    /** Words too common to distinguish one page from another. */
    private val STOPWORDS = setOf(
        "the", "and", "was", "were", "that", "this", "with", "for", "you", "your", "our",
        "about", "what", "when", "there", "then", "have", "has", "had", "she", "her", "him",
        "his", "they", "them", "not", "but", "are", "into", "from", "some", "any", "day",
    )

    private const val MIN_USEFUL_TRANSCRIPT = 3
    private const val SUMMARY_CHARS = 52

    private val WHITESPACE = Regex("""\s+""")
}
