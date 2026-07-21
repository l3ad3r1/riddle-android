package com.riddleapp.diary

/**
 * Lays a reply out across the page as pen paths, in writing order: left to right, wrapping on whole
 * words, top to bottom. Deliberately simple — Latin text only. (The fork this borrows glyph tracing
 * from carries a full grapheme/CJK/RTL planner; that is far more than this page needs.)
 */
class ReplyScript(private val glyphs: GlyphStrokes) {

    /** A page-space polyline, ready to be drawn as one pen stroke. */
    class Stroke(val xs: FloatArray, val ys: FloatArray) {
        val size: Int get() = xs.size
    }

    fun layout(
        text: String,
        left: Float,
        top: Float,
        maxWidth: Float,
        lineHeight: Float,
    ): List<Stroke> {
        val strokes = ArrayList<Stroke>()
        var penX = left
        var lineTop = top

        for (word in splitKeepingSpaces(text)) {
            if (word == NEWLINE) {
                penX = left
                lineTop += lineHeight
                continue
            }
            val advance = glyphs.measure(word)
            // Wrap before a word that would overrun, but never strand a leading space on a new line.
            if (word.isNotBlank() && penX + advance > left + maxWidth && penX > left) {
                penX = left
                lineTop += lineHeight
            }
            for (ch in word) {
                val glyph = glyphs.glyph(ch.toString())
                if (glyph.strokes.isNotEmpty()) {
                    val boxLeft = penX - glyphs.padding
                    for (path in glyph.strokes) {
                        val xs = FloatArray(path.size)
                        val ys = FloatArray(path.size)
                        for (i in path.indices) {
                            xs[i] = boxLeft + path[i].x * glyph.width
                            ys[i] = lineTop + path[i].y * glyph.height
                        }
                        strokes += Stroke(xs, ys)
                    }
                }
                penX += glyph.advance
            }
        }
        return strokes
    }

    /**
     * Splits into words with their trailing spaces attached, so wrapping can measure whole words.
     * Line breaks survive as their own token — a guide or a listed reply needs real lines.
     */
    private fun splitKeepingSpaces(text: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        for (ch in text) {
            if (ch == '\n') {
                if (current.isNotEmpty()) { parts += current.toString(); current.clear() }
                parts += NEWLINE
                continue
            }
            current.append(ch)
            if (ch == ' ') { parts += current.toString(); current.clear() }
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    private companion object {
        /** Sentinel for a forced line break; the splitter emits no bare newline otherwise. */
        const val NEWLINE = "\n"
    }
}
