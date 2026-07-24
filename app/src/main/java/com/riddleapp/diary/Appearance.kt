package com.riddleapp.diary

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * How the page looks: the hand the diary writes in, and whether it is day or night paper.
 * Kept in one place so the view, the settings screen and the page renderer cannot disagree.
 */
object Appearance {

    /**
     * A reply hand. [resourceName] is resolved at runtime rather than through `R.font`, so a font
     * that is not in the repository can simply be dropped into `res/font/` and used — and its
     * absence is not a compile error.
     */
    enum class Hand(val key: String, val label: String, val resourceName: String?) {
        DANCING_SCRIPT("dancing_script", "Dancing Script", "dancing_script"),
        AQUILINE("aquiline", "Aquiline", "aquiline"),
        SYSTEM_CURSIVE("system", "System cursive", null);

        companion object {
            fun from(key: String?): Hand = entries.firstOrNull { it.key == key } ?: DANCING_SCRIPT
        }
    }

    /** The hands actually present on this build — a font that was never bundled is not offered. */
    fun availableHands(context: Context): List<Hand> = Hand.entries.filter { hand ->
        hand.resourceName == null || fontId(context, hand.resourceName) != 0
    }

    /**
     * What the diary writes in before anything is chosen: the optional local hand when it is
     * present, otherwise the bundled one. Keeps adding the setting from changing the page.
     */
    fun defaultHand(context: Context): Hand =
        if (fontId(context, Hand.AQUILINE.resourceName!!) != 0) Hand.AQUILINE else Hand.DANCING_SCRIPT

    fun typeface(context: Context, hand: Hand): Typeface {
        val id = hand.resourceName?.let { fontId(context, it) } ?: 0
        if (id != 0) {
            runCatching { ResourcesCompat.getFont(context, id) }.getOrNull()?.let { return it }
        }
        return Typeface.create("cursive", Typeface.NORMAL)
    }

    private fun fontId(context: Context, name: String): Int =
        context.resources.getIdentifier(name, "font", context.packageName)

    /** The page's colours. Night is not an inversion — it is ink on dark paper, still warm. */
    data class Palette(
        val background: Int,
        val stroke: Int,
        val reply: Int,
        val edge: Int,
    )

    fun palette(context: Context, dark: Boolean): Palette {
        fun color(id: Int) = androidx.core.content.ContextCompat.getColor(context, id)
        return if (dark) {
            Palette(
                background = color(R.color.night_background),
                stroke = color(R.color.night_ink_stroke),
                reply = color(R.color.night_ink_reply),
                edge = color(R.color.night_edge),
            )
        } else {
            Palette(
                background = color(R.color.ink_background),
                stroke = color(R.color.ink_stroke),
                reply = color(R.color.ink_reply),
                edge = color(R.color.parchment_edge),
            )
        }
    }
}
