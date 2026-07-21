package com.riddleapp.diary

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads the pen strokes on a page into text, entirely on-device (ML Kit digital ink). The point is
 * memory: a transcribed page can ride along with later requests as a line of text instead of a
 * ~14 KB image, so the diary can carry far more of the conversation for far less payload.
 *
 * Approach follows `MlKitHandwritingRecognizer` from dc-daichao95/riddleInAndriod (MIT), a fork of
 * MaximeRivest/riddle (MIT).
 */
class HandwritingRecognizer(private val languageTag: String = "en-US") {

    private val modelManager = RemoteModelManager.getInstance()
    private val downloadLock = Mutex()

    /**
     * Several readings of the same page, best first. Recognition offers alternatives and the best
     * one is not always the intended words, so callers matching a phrase can consider them all.
     */
    data class Reading(val best: String, val candidates: List<String>)

    /** Null when the page could not be read — recognition is best-effort and never fatal. */
    suspend fun recognize(strokes: List<List<Triple<Float, Float, Float>>>): Reading? =
        withContext(Dispatchers.Default) {
            if (strokes.isEmpty()) return@withContext null
            val model = model() ?: return@withContext null
            try {
                ensureModel(model)
                val recognizer = DigitalInkRecognition.getClient(
                    DigitalInkRecognizerOptions.builder(model).build()
                )
                try {
                    val candidates = recognizer.recognize(strokes.toInk()).await()
                        .candidates.mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }
                    // Deliberately not logged: this is what the pen wrote, and it stays on the page.
                    candidates.firstOrNull()?.let { Reading(it, candidates) }
                } finally {
                    recognizer.close()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "recognition failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

    /**
     * Downloads the language model on first use. Serialized, and non-cancellable once started, so a
     * second page cannot kick off a duplicate download of the same model.
     */
    private suspend fun ensureModel(model: DigitalInkRecognitionModel) {
        if (modelManager.isModelDownloaded(model).await()) return
        downloadLock.withLock {
            if (modelManager.isModelDownloaded(model).await()) return
            Log.i(TAG, "downloading handwriting model $languageTag")
            withContext(NonCancellable) {
                modelManager.download(model, DownloadConditions.Builder().build()).await()
            }
            Log.i(TAG, "handwriting model ready")
        }
    }

    private fun model(): DigitalInkRecognitionModel? = try {
        DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
            ?.let { DigitalInkRecognitionModel.builder(it).build() }
    } catch (e: MlKitException) {
        Log.w(TAG, "unsupported language $languageTag")
        null
    }

    /**
     * ML Kit needs only temporal order, and our stored strokes carry no timestamps, so point order
     * is encoded as synthetic milliseconds that keep increasing across stroke boundaries.
     */
    private fun List<List<Triple<Float, Float, Float>>>.toInk(): Ink {
        var t = 0L
        return Ink.builder().apply {
            for (stroke in this@toInk) {
                if (stroke.isEmpty()) continue
                val builder = Ink.Stroke.builder()
                for ((x, y, _) in stroke) builder.addPoint(Ink.Point.create(x, y, t++))
                addStroke(builder.build())
            }
        }.build()
    }

    private companion object {
        const val TAG = "DiaryRecognizer"
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
