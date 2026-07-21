package com.riddleapp.diary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Recall is matched against handwriting-recognition output, which is noisy and cannot be produced
 * from adb, so the parsing and search rules are pinned here instead.
 */
class RecallTest {

    private fun page(id: Long, transcript: String, reply: String = "an answer") =
        MemoryStore.PastPage(id, reply, File("$id.png"), transcript)

    @Test
    fun `recognises the remember question`() {
        assertTrue(Recall.parse("What do you remember?") is Recall.Intent.Index)
        assertTrue(Recall.parse("what do you recall") is Recall.Intent.Index)
    }

    @Test
    fun `recognises a search and extracts the subject`() {
        assertEquals(
            Recall.Intent.Search("the garden"),
            Recall.parse("Show me what I wrote about the garden."),
        )
        assertEquals(
            Recall.Intent.Search("my brother"),
            Recall.parse("what did i write about my brother?"),
        )
    }

    @Test
    fun `misread handwriting still counts as a recall`() {
        // Realistic recognizer slips: joined letters, a dropped letter, a swapped one.
        assertTrue(Recall.parse("what do you rememher") is Recall.Intent.Index)
        assertTrue(Recall.parse("whot do you remember") is Recall.Intent.Index)
        assertTrue(Recall.parse("what do you rernember") is Recall.Intent.Index)
    }

    @Test
    fun `a recall is found among alternative readings`() {
        val candidates = listOf("wlat lo you rernernher", "what do you remember")
        assertTrue(Recall.parse(candidates) is Recall.Intent.Index)
    }

    @Test
    fun `a misread search subject is corrected against what is remembered`() {
        val pages = listOf(page(2, "I planted beans in the garden"), page(1, "the sea was grey"))
        // "gardn" is not a word the diary knows; "garden" is.
        assertEquals(2L, Recall.search(pages, "gardn")?.id)
        assertEquals(2L, Recall.search(pages, "beens")?.id)
    }

    @Test
    fun `correction leaves genuinely unknown words alone`() {
        val pages = listOf(page(1, "the sea was grey"))
        assertNull(Recall.search(pages, "helicopters"))
    }

    @Test
    fun `a lone question mark summons the guide`() {
        assertEquals(Recall.Intent.Guide, Recall.parse("?"))
        // Recognition often reads one large mark as two.
        assertEquals(Recall.Intent.Guide, Recall.parse("??"))
        assertEquals(Recall.Intent.Guide, Recall.parse(" ? "))
    }

    @Test
    fun `a question mark inside a sentence does not summon the guide`() {
        assertNull(Recall.parse("is it raining?"))
        assertEquals(Recall.Intent.Index, Recall.parse("what do you remember?"))
    }

    @Test
    fun `ordinary writing is not a recall`() {
        assertNull(Recall.parse("Today I planted seeds in the garden."))
        assertNull(Recall.parse("I remember the sea."))
        assertNull(Recall.parse("beans in the garden"))
        assertNull(Recall.parse("what a strange dream I had last night"))
        assertNull(Recall.parse(""))
        assertNull(Recall.parse(null))
    }

    @Test
    fun `search finds the page whose words match`() {
        val pages = listOf(
            page(3, "the sea was grey today"),
            page(2, "I planted beans in the garden"),
            page(1, "nothing much happened"),
        )
        assertEquals(2L, Recall.search(pages, "the garden")?.id)
        assertEquals(3L, Recall.search(pages, "sea")?.id)
    }

    @Test
    fun `search gives nothing when no page matches`() {
        assertNull(Recall.search(listOf(page(1, "the sea")), "mountains"))
    }

    @Test
    fun `common words alone never match a page`() {
        val pages = listOf(page(2, "the sea was grey"), page(1, "the garden was green"))
        // Every page contains "the" and "was"; matching them is not evidence of anything.
        assertNull(Recall.search(pages, "the"))
        assertNull(Recall.search(pages, "what about the"))
        // A real subject alongside common words still finds its page.
        assertEquals(1L, Recall.search(pages, "the garden")?.id)
    }

    @Test
    fun `index summarises what is remembered`() {
        assertTrue(Recall.indexText(emptyList()).contains("nothing yet"))
        val text = Recall.indexText(listOf(page(1, "the sea"), page(2, "the garden")))
        assertTrue(text.contains("2 pages"))
        assertTrue(text.contains("the sea"))
    }

    @Test
    fun `index puts each page on its own line`() {
        val text = Recall.indexText(listOf(page(1, "the sea"), page(2, "the garden")))
        // Header plus one line per page.
        assertEquals(3, text.lines().size)
    }

    @Test
    fun `a scribble transcript falls back to the reply`() {
        val text = Recall.indexText(listOf(page(1, transcript = "\\", reply = "a single stroke")))
        assertTrue(text.contains("a single stroke"))
        assertFalse(text.contains("\\"))
    }
}
