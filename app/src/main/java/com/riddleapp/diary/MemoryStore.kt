package com.riddleapp.diary

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every finished page is kept on the tablet: the committed page image, the reply, and the raw pen
 * strokes. Mirrors riddle's `memory.rs` — plain files, no database, delete the folder and the diary
 * forgets. Strokes are stored even though nothing replays them yet, so the "conjure the past"
 * gesture can be added later without migrating existing memories.
 */
class MemoryStore(context: Context) {

    private val dir = File(context.filesDir, "memories").apply { mkdirs() }

    data class PastPage(
        val id: Long,
        val reply: String,
        val image: File,
        /** What the pen wrote, when recognition succeeded. Empty for pages stored before it existed. */
        val transcript: String,
    )

    fun save(
        pageImage: ByteArray,
        strokes: List<List<Triple<Float, Float, Float>>>,
        transcript: String?,
        reply: String,
    ) {
        try {
            val id = System.currentTimeMillis()
            File(dir, "$id.png").writeBytes(pageImage)

            val strokesJson = JSONArray()
            for (stroke in strokes) {
                val points = JSONArray()
                for ((x, y, pressure) in stroke) {
                    points.put(JSONArray().put(x.toDouble()).put(y.toDouble()).put(pressure.toDouble()))
                }
                strokesJson.put(points)
            }

            val meta = JSONObject()
                .put("id", id)
                .put("reply", reply)
                .put("transcript", transcript.orEmpty())
                .put("strokes", strokesJson)
            File(dir, "$id.json").writeText(meta.toString())

            prune()
        } catch (e: Exception) {
            // A page that cannot be remembered must not break the page you just wrote.
            Log.w(TAG, "could not save page: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** The most recent [count] pages, oldest first, so they read as a conversation. */
    fun recent(count: Int): List<PastPage> = try {
        pageIds().takeLast(count).mapNotNull { id ->
            val image = File(dir, "$id.png")
            val metaFile = File(dir, "$id.json")
            if (!image.exists() || !metaFile.exists()) return@mapNotNull null
            val meta = JSONObject(metaFile.readText())
            val reply = meta.optString("reply")
            if (reply.isEmpty()) null
            else PastPage(id, reply, image, meta.optString("transcript"))
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read memories: ${e.javaClass.simpleName}: ${e.message}")
        emptyList()
    }

    /** Every remembered page, newest first — for searching and for the "what do you remember" index. */
    fun allPages(): List<PastPage> = try {
        pageIds().reversed().mapNotNull { id ->
            val metaFile = File(dir, "$id.json")
            if (!metaFile.exists()) return@mapNotNull null
            val meta = JSONObject(metaFile.readText())
            val reply = meta.optString("reply")
            if (reply.isEmpty()) null
            else PastPage(id, reply, File(dir, "$id.png"), meta.optString("transcript"))
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not list memories: ${e.javaClass.simpleName}: ${e.message}")
        emptyList()
    }

    /** The original pen strokes of a remembered page, so it can be rewritten in front of you. */
    fun strokesOf(id: Long): List<List<Triple<Float, Float, Float>>> = try {
        val meta = JSONObject(File(dir, "$id.json").readText())
        val strokesJson = meta.optJSONArray("strokes") ?: JSONArray()
        buildList {
            for (s in 0 until strokesJson.length()) {
                val points = strokesJson.optJSONArray(s) ?: continue
                val stroke = ArrayList<Triple<Float, Float, Float>>(points.length())
                for (p in 0 until points.length()) {
                    val point = points.optJSONArray(p) ?: continue
                    stroke += Triple(
                        point.optDouble(0).toFloat(),
                        point.optDouble(1).toFloat(),
                        point.optDouble(2, 1.0).toFloat(),
                    )
                }
                if (stroke.size > 1) add(stroke)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read strokes: ${e.javaClass.simpleName}: ${e.message}")
        emptyList()
    }

    fun forget() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun count(): Int = pageIds().size

    /** Sorted oldest → newest. */
    private fun pageIds(): List<Long> =
        dir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { it.nameWithoutExtension.toLongOrNull() }
            ?.sorted()
            ?: emptyList()

    private fun prune() {
        val ids = pageIds()
        if (ids.size <= MAX_PAGES) return
        for (id in ids.take(ids.size - MAX_PAGES)) {
            File(dir, "$id.png").delete()
            File(dir, "$id.json").delete()
        }
    }

    companion object {
        private const val TAG = "DiaryMemory"

        /** Matches riddle's cap — the last ~400 pages are kept. */
        private const val MAX_PAGES = 400

        /**
         * How many past pages ride along with each request. Transcribed pages cost a line of text
         * rather than a ~14 KB image, so this can be generous; untranscribed ones (stored before
         * recognition existed, or where it failed) still fall back to the image.
         */
        const val CONTEXT_PAGES = 10
    }
}
