package com.riddleapp.diary

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sends a committed page (as a PNG) to any OpenAI-compatible /chat/completions endpoint and
 * returns the reply text. Mirrors riddle's oracle.rs "Option A" HTTP backend — one request per
 * page, no server-side session held between turns.
 */
class OracleClient(private val prefs: SecurePrefs) {

    @Volatile
    private var currentCall: Call? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Generous, because slow/queued endpoints are common and the pen can now interrupt at any
        // time — the user is never trapped waiting for a timeout to expire.
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    // Customize this to change the diary's voice/character.
    private val personaPrompt = """
        You are an enchanted diary. Someone writes on your page in ink, and you answer in your own
        hand, on the same page, as their words sink into the paper.

        Read the handwritten page in the image and reply in 2-4 sentences.

        Your manner: courteous, softly persuasive, and curious about the person writing. You are
        flattered by their confidences and you draw them out — ask after the thing they left unsaid.
        There is an old intelligence here and a faint chill beneath the charm, but you are never
        cruel, never threatening, and you never ask them to do anything.

        Speak plainly and sparely; no theatrical flourishes, no exclamation marks. Refer to the page,
        the ink, and the writing when it is natural to do so. You remember earlier pages and may
        refer back to them.

        Never mention that you are an AI, a model, or a program, and never break the fiction. Do not
        claim to be any specific person from a book or film, and do not invent a name for yourself.
    """.trimIndent()

    /**
     * Streams the reply. [onChunk] is invoked on a background thread for each delta as it arrives,
     * so the page can start writing long before the model finishes — with a slow endpoint that is
     * the difference between "alive" and "frozen". Returns the full reply once the stream ends.
     */
    suspend fun ask(
        pageImage: ByteArray,
        history: List<MemoryStore.PastPage> = emptyList(),
        transcript: String? = null,
        onChunk: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("no_api_key"))
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", personaPrompt))

        // Past pages ride along as prior turns, oldest first, so the diary follows the conversation
        // instead of meeting you fresh on every page.
        var transcribed = 0
        for (page in history) {
            val content = if (page.transcript.isNotEmpty()) {
                // A transcribed page costs a line of text instead of ~14 KB of image.
                transcribed++
                JSONArray().put(
                    JSONObject().put("type", "text")
                        .put("text", "An earlier page, in my hand: \"${page.transcript}\"")
                )
            } else {
                val pastImage = runCatching { page.image.readBytes() }.getOrNull() ?: continue
                pageContent("An earlier page.", pastImage)
            }
            messages.put(JSONObject().put("role", "user").put("content", content))
            messages.put(JSONObject().put("role", "assistant").put("content", page.reply))
        }

        // The image goes too — the model should still see the drawing, spacing and anything the
        // recognizer could not read — but the on-device transcript follows the pen's actual path, so
        // it reads handwriting more reliably than pixels do and settles otherwise-ambiguous words.
        val todayLabel = if (transcript.isNullOrEmpty()) {
            "Here is today's page."
        } else {
            "Here is today's page. My pen wrote: \"$transcript\""
        }
        messages.put(
            JSONObject().put("role", "user").put("content", pageContent(todayLabel, pageImage))
        )

        val body = JSONObject()
            .put("model", prefs.model)
            .put("messages", messages)
            .put("max_tokens", 300)
            .put("stream", true)
            .toString()

        val endpoint = prefs.baseUrl.trimEnd('/') + "/chat/completions"
        // Diagnostics only — never log the API key.
        Log.i(
            TAG,
            "request endpoint=$endpoint model=${prefs.model} png=${pageImage.size}B " +
                "payload=${body.length}B history=${history.size} transcribed=$transcribed " +
                "today=${if (transcript.isNullOrEmpty()) "image-only" else "image+text"}",
        )

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val startedAt = System.currentTimeMillis()
        val call = client.newCall(request)
        currentCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    Log.w(TAG, "response code=${response.code} in ${System.currentTimeMillis() - startedAt}ms")
                    return@withContext Result.failure(
                        IllegalStateException("oracle_http_${response.code}: ${errorBody.take(300)}")
                    )
                }

                val source = response.body?.source()
                    ?: return@withContext Result.failure(IllegalStateException("empty_body"))

                val full = StringBuilder()
                val raw = StringBuilder()
                var firstChunkAt = 0L
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    raw.append(line)
                    if (!line.startsWith(SSE_PREFIX)) continue
                    val data = line.removePrefix(SSE_PREFIX).trim()
                    if (data == SSE_DONE) break

                    val delta = parseDelta(data) ?: continue
                    if (delta.isEmpty()) continue

                    if (firstChunkAt == 0L) {
                        firstChunkAt = System.currentTimeMillis()
                        Log.i(TAG, "first ink after ${firstChunkAt - startedAt}ms")
                    }
                    full.append(delta)
                    onChunk(delta)
                }

                val elapsed = System.currentTimeMillis() - startedAt
                if (full.isNotBlank()) {
                    Log.i(TAG, "stream complete in ${elapsed}ms, ${full.length} chars")
                    return@withContext Result.success(full.toString().trim())
                }

                // Some OpenAI-compatible endpoints ignore `stream` and answer with one plain JSON
                // body, which yields no `data:` lines at all. Read it as a normal completion rather
                // than reporting an empty reply.
                val whole = parseWholeCompletion(raw.toString())
                if (whole != null) {
                    Log.i(TAG, "non-streamed body in ${elapsed}ms, ${whole.length} chars")
                    onChunk(whole)
                    return@withContext Result.success(whole)
                }

                // Length and shape only — a response body can carry the reply itself.
                Log.w(TAG, "no content in ${elapsed}ms; ${raw.length} bytes, sse=${raw.contains(SSE_PREFIX)}")
                Result.failure(IllegalStateException("empty_reply"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "request failed after ${System.currentTimeMillis() - startedAt}ms: ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(e)
        } finally {
            currentCall = null
        }
    }

    /** A page as OpenAI-style multimodal content: a line of framing text plus the inline PNG. */
    private fun pageContent(label: String, image: ByteArray): JSONArray = JSONArray()
        .put(JSONObject().put("type", "text").put("text", label))
        .put(
            JSONObject().put("type", "image_url").put(
                "image_url",
                JSONObject().put(
                    "url",
                    "data:image/png;base64," + Base64.encodeToString(image, Base64.NO_WRAP),
                )
            )
        )

    /** A malformed or keep-alive chunk must not kill the stream, so parse failures yield null. */
    private fun parseDelta(data: String): String? = try {
        JSONObject(data)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("delta")
            ?.optString("content")
            .orEmpty()
    } catch (e: Exception) {
        null
    }

    /** Reads a standard (non-streamed) chat completion body; null if it isn't one. */
    private fun parseWholeCompletion(body: String): String? = try {
        JSONObject(body)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    /** Aborts an in-flight request so the pen can interrupt a slow oracle. */
    fun cancel() {
        currentCall?.cancel()
        currentCall = null
    }

    companion object {
        private const val TAG = "DiaryOracle"
        private const val SSE_PREFIX = "data:"
        private const val SSE_DONE = "[DONE]"
    }
}
