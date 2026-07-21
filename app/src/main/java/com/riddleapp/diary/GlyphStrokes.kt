package com.riddleapp.diary

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.max

/**
 * Turns text into pen paths so the reply can be *written* rather than typed: each character is
 * rasterized from the reply typeface, thinned to a single-pixel skeleton (Zhang-Suen), then traced
 * into polylines normalized to 0..1 within the glyph box.
 *
 * This is riddle's `ink.rs`/`script.rs` pipeline (rasterize → thin → trace → replay). The Android
 * adaptation of the thinning and tracing follows `AndroidTypefaceReplyGlyphSource` from
 * dc-daichao95/riddleInAndriod (MIT), itself a fork of MaximeRivest/riddle (MIT).
 */
class GlyphStrokes(typeface: Typeface, textSizePixels: Float = 96f) {

    data class Point(val x: Float, val y: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = textSizePixels
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    /** Tracing a glyph is expensive, and a reply reuses the same handful of characters. */
    private val cache = object : LinkedHashMap<String, Glyph>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Glyph>?): Boolean = size > 512
    }

    /** [strokes] are normalized to the glyph box; [advance] is in the same units as the text size. */
    data class Glyph(val strokes: List<List<Point>>, val advance: Float, val width: Float, val height: Float)

    /** Ink starts this far into the glyph box on both axes; layout needs it to align baselines. */
    val padding: Float = PADDING.toFloat()

    fun glyph(cluster: String): Glyph = cache.getOrPut(cluster) { trace(cluster) }

    fun measure(text: String): Float = paint.measureText(text)

    private fun trace(cluster: String): Glyph {
        val advance = paint.measureText(cluster)
        if (cluster.isBlank()) return Glyph(emptyList(), advance, 0f, 0f)

        val metrics = paint.fontMetrics
        val w = (ceil(advance).toInt() + PADDING * 2).coerceIn(1, MAX_DIMENSION)
        val h = (ceil(metrics.descent - metrics.ascent).toInt() + PADDING * 2).coerceIn(1, MAX_DIMENSION)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(bitmap).drawText(cluster, PADDING.toFloat(), PADDING - metrics.ascent, paint)
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val mask = BooleanArray(pixels.size) { Color.alpha(pixels[it]) >= ALPHA_THRESHOLD }
            thin(mask, w, h)
            Glyph(tracePaths(mask, w, h), advance, w.toFloat(), h.toFloat())
        } finally {
            bitmap.recycle()
        }
    }

    /** Zhang-Suen thinning: erode the filled glyph down to a one-pixel-wide skeleton. */
    private fun thin(mask: BooleanArray, width: Int, height: Int) {
        fun index(x: Int, y: Int) = y * width + x
        var changed: Boolean
        var rounds = 0
        do {
            changed = false
            repeat(2) { phase ->
                val clear = ArrayList<Int>()
                for (y in 1 until height - 1) {
                    for (x in 1 until width - 1) {
                        if (!mask[index(x, y)]) continue
                        val p = booleanArrayOf(
                            mask[index(x, y - 1)], mask[index(x + 1, y - 1)], mask[index(x + 1, y)],
                            mask[index(x + 1, y + 1)], mask[index(x, y + 1)], mask[index(x - 1, y + 1)],
                            mask[index(x - 1, y)], mask[index(x - 1, y - 1)],
                        )
                        val neighbors = p.count { it }
                        if (neighbors !in 2..6) continue
                        val transitions = p.indices.count { !p[it] && p[(it + 1) % p.size] }
                        if (transitions != 1) continue
                        val keep = if (phase == 0) {
                            !(p[0] && p[2] && p[4]) && !(p[2] && p[4] && p[6])
                        } else {
                            !(p[0] && p[2] && p[6]) && !(p[0] && p[4] && p[6])
                        }
                        if (keep) clear += index(x, y)
                    }
                }
                if (clear.isNotEmpty()) {
                    changed = true
                    clear.forEach { mask[it] = false }
                }
            }
            rounds++
        } while (changed && rounds < MAX_THINNING_ROUNDS)
    }

    /** Walks the skeleton into polylines, preferring endpoints so strokes start where a pen would. */
    private fun tracePaths(mask: BooleanArray, width: Int, height: Int): List<List<Point>> {
        fun ink(x: Int, y: Int) = x in 0 until width && y in 0 until height && mask[y * width + x]
        fun neighbors(x: Int, y: Int): List<Pair<Int, Int>> = buildList {
            for (dy in -1..1) for (dx in -1..1) {
                if ((dx != 0 || dy != 0) && ink(x + dx, y + dy)) add(x + dx to y + dy)
            }
        }

        val starts = ArrayList<Pair<Int, Int>>()
        for (y in 0 until height) for (x in 0 until width) {
            if (ink(x, y) && neighbors(x, y).size == 1) starts += x to y
        }
        // Then everything else, so closed loops (o, e) still get traced.
        for (y in 0 until height) for (x in 0 until width) if (ink(x, y)) starts += x to y

        val visited = BooleanArray(mask.size)
        val paths = ArrayList<List<Point>>()
        for (start in starts) {
            if (visited[start.second * width + start.first]) continue
            val path = ArrayList<Point>()
            var current = start
            while (true) {
                visited[current.second * width + current.first] = true
                path += Point(
                    current.first.toFloat() / max(1, width - 1),
                    current.second.toFloat() / max(1, height - 1),
                )
                val next = neighbors(current.first, current.second)
                    .firstOrNull { !visited[it.second * width + it.first] } ?: break
                current = next
            }
            when {
                path.size >= 2 -> paths += path
                // A period or apostrophe thins down to a single pixel. Dropping one-point paths
                // would silently swallow all of them, so emit a hair-length stroke instead — with a
                // round cap that draws as a dot.
                path.size == 1 -> {
                    val dx = 1f / max(1, width - 1)
                    paths += listOf(path[0], Point(path[0].x + dx, path[0].y))
                }
            }
        }
        // Left-to-right, so a glyph is drawn roughly in writing order.
        return paths.sortedBy { path -> path.minOf(Point::x) }
    }

    private companion object {
        const val PADDING = 4
        const val MAX_DIMENSION = 512
        const val ALPHA_THRESHOLD = 128
        const val MAX_THINNING_ROUNDS = 512
    }
}
