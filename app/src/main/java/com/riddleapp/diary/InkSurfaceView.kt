package com.riddleapp.diary

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The page. Captures pen strokes, waits for the pen to rest, sends the page to the oracle, and
 * writes the reply back stroke by stroke — the reply is traced into pen paths by [GlyphStrokes] and
 * laid out by [ReplyScript], so it is drawn rather than typed.
 */
class InkSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private enum class State { WRITING, THINKING, REPLYING, RECALLING, FAILED, GUIDE }

    private data class StrokePoint(val x: Float, val y: Float, val pressure: Float)

    private val strokes = mutableListOf<MutableList<StrokePoint>>()
    private var currentStroke: MutableList<StrokePoint>? = null

    private var state = State.WRITING
    private var fadeAlpha = 255
    private var replyText = ""
    private var replyComplete = false
    private var thinkingDots = 0
    private var oracleJob: Job? = null
    private var penPointerId = MotionEvent.INVALID_POINTER_ID

    private val prefs = SecurePrefs(context)
    private val oracle = OracleClient(prefs)
    private val memory = MemoryStore(context)
    private val recognizer = HandwritingRecognizer()
    private val viewJob = Job()
    private val viewScope = CoroutineScope(Dispatchers.Main + viewJob)

    private val handler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { commitPage() }
    /**
     * Advances the pen along the traced reply a few points per tick. Because the reply streams in,
     * the runnable keeps itself scheduled while text may still arrive — it idles rather than
     * stopping when it catches up to what the oracle has sent so far.
     */
    private val revealRunnable = object : Runnable {
        override fun run() {
            if (state != State.REPLYING && state != State.FAILED && state != State.GUIDE) return
            val total = replyStrokes.sumOf { it.size }
            if (revealedPoints < total) {
                // The guide is reference, not a reply — it should not be savoured.
                revealedPoints += if (state == State.GUIDE) POINTS_PER_TICK * 4 else POINTS_PER_TICK
                invalidate()
            } else if (replyComplete) {
                // Written out in full. A reply is then left to be read and allowed to sink away;
                // the guide and a failure notice stay until the pen dismisses them.
                if (state == State.REPLYING) scheduleReplyFade()
                return
            }
            handler.postDelayed(this, REVEAL_TICK_MS)
        }
    }

    /**
     * Rewrites the remembered page in your own hand first, then lets the old answer follow, so the
     * page appears to surface rather than simply be printed.
     */
    private val recallRunnable = object : Runnable {
        override fun run() {
            if (state != State.RECALLING) return
            val handTotal = recalledStrokes.sumOf { it.size }
            if (recalledRevealed < handTotal) {
                recalledRevealed += POINTS_PER_TICK
            } else if (revealedPoints < replyStrokes.sumOf { it.size }) {
                revealedPoints += POINTS_PER_TICK
            } else {
                return
            }
            invalidate()
            handler.postDelayed(this, REVEAL_TICK_MS)
        }
    }

    /** Keeps the thinking indicator visibly alive during a long wait. */
    private val thinkingRunnable = object : Runnable {
        override fun run() {
            if (state != State.THINKING) return
            thinkingDots = (thinkingDots + 1) % 4
            invalidate()
            handler.postDelayed(this, THINKING_TICK_MS)
        }
    }

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink_stroke)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * The reply hand. Prefers an optional local font — drop `aquiline.ttf` into `res/font/` and it is
     * used automatically — otherwise the bundled Dancing Script. Looked up by name rather than by
     * `R.font.aquiline` so the project still builds when that file is absent, which it is in the
     * public repository: the font ships without licence information, so it is not redistributed.
     */
    private val replyTypeface: Typeface = loadReplyTypeface(context)

    private fun loadReplyTypeface(context: Context): Typeface {
        val optional = resources.getIdentifier("aquiline", "font", context.packageName)
        if (optional != 0) {
            runCatching { ResourcesCompat.getFont(context, optional) }.getOrNull()?.let { return it }
        }
        return ResourcesCompat.getFont(context, R.font.dancing_script)
            ?: Typeface.create("cursive", Typeface.NORMAL)
    }

    private val replyGlyphs = GlyphStrokes(replyTypeface, REPLY_TEXT_PX * resources.displayMetrics.density)
    private val replyScript = ReplyScript(replyGlyphs)

    /** The reply as traced pen paths, and how many points of it have been "written" so far. */
    private var replyStrokes: List<ReplyScript.Stroke> = emptyList()
    private var revealedPoints = 0
    private var layoutJob: Job? = null

    /** The reply's own ink, which sinks back into the paper once it has been written and read. */
    private var replyAlpha = 255
    private var replyFadeAnimator: ValueAnimator? = null

    /** A conjured page: the original pen strokes, and how much of them has surfaced. */
    private var recalledStrokes: List<List<Triple<Float, Float, Float>>> = emptyList()
    private var recalledRevealed = 0

    /** Where a conjured page's date and old reply begin; null for today's page (uses the margin). */
    private var replyTopOverride: Float? = null

    /** Remembered ink is faded, so a recalled page never reads as today's page. */
    private val fadedInkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink_stroke)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f * resources.displayMetrics.density
        alpha = RECALL_ALPHA
    }

    private val replyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink_reply)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private val thinkingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink_reply)
        alpha = 140
        textSize = 40f
    }

    private var fadeAnimator: ValueAnimator? = null

    /** Aged paper is darker at its edges than its middle; this is that, and nothing more. */
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val edge = ContextCompat.getColor(context, R.color.parchment_edge)
        vignettePaint.shader = RadialGradient(
            w / 2f,
            h / 2f,
            maxOf(w, h) * 0.75f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, ColorUtils.setAlphaComponent(edge, 46)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /**
     * Pen-only surface. Every event is consumed so nothing else reacts to a resting palm, but only
     * the stylus draws. A palm and the pen are often down at once, so the pen is tracked by pointer
     * id rather than by pointer 0 — otherwise whichever touch landed first would win.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                if (!isPen(event.getToolType(index))) return true

                // The pen must always be live: coming down while the diary is thinking or replying
                // abandons that page (cancelling any in-flight request) and starts this stroke,
                // rather than discarding input for the length of a slow call. A failed page is the
                // exception — its writing is kept, so the pen simply resumes it.
                when (state) {
                    State.FAILED -> resumeFailedPage()
                    State.WRITING -> Unit
                    else -> abandonPage()
                }

                handler.removeCallbacks(idleRunnable)
                penPointerId = event.getPointerId(index)
                if (isEraser(event.getToolType(index))) {
                    eraseNear(event.getX(index), event.getY(index))
                } else {
                    currentStroke = mutableListOf(
                        StrokePoint(event.getX(index), event.getY(index), event.getPressure(index))
                    )
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (penPointerId == MotionEvent.INVALID_POINTER_ID) return true
                val index = event.findPointerIndex(penPointerId)
                if (index < 0) return true

                if (isEraser(event.getToolType(index))) {
                    eraseNear(event.getX(index), event.getY(index))
                } else {
                    val stroke = currentStroke ?: return true
                    for (h in 0 until event.historySize) {
                        stroke.add(
                            StrokePoint(
                                event.getHistoricalX(index, h),
                                event.getHistoricalY(index, h),
                                event.getHistoricalPressure(index, h),
                            )
                        )
                    }
                    stroke.add(
                        StrokePoint(event.getX(index), event.getY(index), event.getPressure(index))
                    )
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) != penPointerId) return true
                finishStroke()
            }

            MotionEvent.ACTION_CANCEL -> finishStroke()
        }
        invalidate()
        return true
    }

    private fun finishStroke() {
        currentStroke?.let { if (it.size > 1) strokes.add(it) }
        currentStroke = null
        penPointerId = MotionEvent.INVALID_POINTER_ID
        scheduleIdleCommit()
    }

    private fun isPen(toolType: Int): Boolean = PenInput.isPen(toolType)

    private fun isEraser(toolType: Int): Boolean = PenInput.isEraser(toolType)

    private fun eraseNear(x: Float, y: Float) {
        val radius = ERASE_RADIUS_DP * resources.displayMetrics.density
        strokes.removeAll { stroke -> stroke.any { p -> distance(p.x, p.y, x, y) < radius } }
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun scheduleIdleCommit() {
        handler.removeCallbacks(idleRunnable)
        if (strokes.isNotEmpty()) {
            handler.postDelayed(idleRunnable, IDLE_MS)
        }
    }

    private fun commitPage() {
        if (strokes.isEmpty() || state != State.WRITING) return

        state = State.THINKING
        fadeAlpha = 255
        thinkingDots = 0
        replyText = ""
        replyComplete = false
        handler.post(thinkingRunnable)
        invalidate()
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofInt(255, 40).apply {
            duration = FADE_MS
            addUpdateListener {
                fadeAlpha = it.animatedValue as Int
                invalidate()
            }
            start()
        }

        val page = renderPageBitmap()
        val pngBytes = ByteArrayOutputStream().use {
            page.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }

        // Snapshot the strokes now: the page is cleared the moment the reply lands or the pen
        // interrupts, but the save happens after the oracle answers.
        val committedStrokes = strokes.map { stroke -> stroke.map { Triple(it.x, it.y, it.pressure) } }
        val remember = prefs.memoryEnabled

        oracleJob = viewScope.launch {
            // Read the page first: a recall is answered from the tablet, so the transcript has to be
            // in hand before deciding whether this page is a question for the oracle at all. Costs a
            // second or two against a reply that takes tens of seconds.
            val readingDeferred = if (remember) {
                async { recognizer.recognize(committedStrokes) }
            } else {
                null
            }
            val reading = readingDeferred?.await()
            val transcript = reading?.best

            if (remember && handleRecall(reading)) return@launch

            val history = if (remember) {
                withContext(Dispatchers.IO) { memory.recent(MemoryStore.CONTEXT_PAGES) }
            } else {
                emptyList()
            }

            val result = oracle.ask(pngBytes, history, transcript) { delta ->
                // Arrives on OkHttp's thread; hop to the main thread to touch view state.
                handler.post { appendReply(delta) }
            }
            result.onSuccess { reply ->
                replyComplete = true
                if (state != State.REPLYING) startReply(reply)
                if (remember) {
                    withContext(Dispatchers.IO) {
                        memory.save(pngBytes, committedStrokes, transcript, reply)
                    }
                }
            }
            result.onFailure { err ->
                // A cancelled call is the user interrupting with the pen, not an error to report.
                if (!isActive) return@onFailure
                showFailure(describeError(err))
            }
        }
    }

    /**
     * Answers a recall from what is already on the tablet, and returns true if the page was one.
     * Nothing is sent, and nothing is stored — asking to see the past is not itself a new page.
     */
    private suspend fun handleRecall(reading: HandwritingRecognizer.Reading?): Boolean {
        val intent = Recall.parse(reading?.candidates.orEmpty())
        // Which gesture fired, and how many readings it had to choose from — never the words.
        Log.i(TAG, "recall intent=${intent ?: "none"} from ${reading?.candidates?.size ?: 0} readings")
        if (intent == null) return false
        val pages = withContext(Dispatchers.IO) { memory.allPages() }

        when (intent) {
            is Recall.Intent.Guide -> showGuide()

            is Recall.Intent.Index -> showRecall(Recall.indexText(pages), emptyList())

            is Recall.Intent.Search -> {
                val found = Recall.search(pages, intent.query)
                if (found == null) {
                    showRecall("I find nothing about \"${intent.query}\".", emptyList())
                } else {
                    val strokes = withContext(Dispatchers.IO) { memory.strokesOf(found.id) }
                    showRecall("${Recall.formatDate(found.id)} - ${found.reply}", strokes)
                }
            }
        }
        return true
    }

    /**
     * Holds the finished reply long enough to be read, then lets it sink back into the paper,
     * leaving a blank page. The hold scales with length, because a long answer needs longer.
     */
    private fun scheduleReplyFade() {
        if (replyFadeAnimator != null) return
        val hold = (REPLY_HOLD_BASE_MS + replyText.length * REPLY_HOLD_PER_CHAR_MS)
            .coerceAtMost(REPLY_HOLD_MAX_MS)
        replyFadeAnimator = ValueAnimator.ofInt(255, 0).apply {
            startDelay = hold
            duration = REPLY_FADE_MS
            addUpdateListener {
                replyAlpha = it.animatedValue as Int
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Only clear if the pen has not already moved the page on to something else.
                    if (state == State.REPLYING) {
                        startNewPage()
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    /**
     * The oracle could not answer. Your writing stays exactly where it was — losing a page after a
     * long wait is the worst thing this app can do — and the reason is written at the foot of it.
     * Touch the pen to carry on writing; resting it again sends the same page anew.
     */
    private fun showFailure(reason: String) {
        state = State.FAILED
        replyText = reason
        replyComplete = true
        revealedPoints = 0
        replyStrokes = emptyList()
        fadeAlpha = 255
        fadeAnimator?.cancel()
        // Keep the reason clear of the writing it refers to.
        val lineHeight = REPLY_TEXT_PX * resources.displayMetrics.density * REPLY_LINE_SPACING
        replyTopOverride = height - lineHeight * 2f
        handler.removeCallbacks(thinkingRunnable)
        handler.removeCallbacks(revealRunnable)
        handler.post(revealRunnable)
        relayoutReply()
        invalidate()
    }

    /** The diary explains itself, in its own hand. Nothing is sent and nothing is stored. */
    private fun showGuide() {
        state = State.GUIDE
        replyText = resources.getString(R.string.guide_text)
        replyComplete = true
        revealedPoints = 0
        replyStrokes = emptyList()
        replyTopOverride = null
        handler.removeCallbacks(thinkingRunnable)
        handler.removeCallbacks(revealRunnable)
        handler.post(revealRunnable)
        relayoutReply()
        invalidate()
    }

    /** The remembered page rises through the paper: your own hand first, then what was answered. */
    private fun showRecall(text: String, pastStrokes: List<List<Triple<Float, Float, Float>>>) {
        state = State.RECALLING
        recalledStrokes = pastStrokes
        // Sit the date and old reply just under the remembered handwriting, so the two never cross.
        val lowestInk = pastStrokes.flatten().maxOfOrNull { it.second }
        replyTopOverride = lowestInk?.plus(RECALL_TEXT_GAP_DP * resources.displayMetrics.density)
        recalledRevealed = 0
        replyText = text
        replyComplete = true
        revealedPoints = 0
        replyStrokes = emptyList()
        handler.removeCallbacks(thinkingRunnable)
        handler.removeCallbacks(revealRunnable)
        handler.post(recallRunnable)
        relayoutReply()
        invalidate()
    }

    /** First streamed delta flips the page into REPLYING; later ones extend the text being revealed. */
    private fun appendReply(delta: String) {
        if (state == State.THINKING) {
            startReply(delta)
            return
        }
        if (state == State.REPLYING) {
            replyText += delta
            relayoutReply()
        }
    }

    /**
     * What the page says when the answer does not come. The common causes get plain words — the raw
     * exception is already in the log under `DiaryOracle` when the detail is actually needed.
     */
    private fun describeError(err: Throwable): String {
        val message = err.message.orEmpty()
        return when {
            message.contains("no_api_key") -> resources.getString(R.string.error_no_key)
            err is java.net.UnknownHostException -> resources.getString(R.string.error_offline)
            err is java.net.SocketTimeoutException -> resources.getString(R.string.error_timeout)
            message.contains("oracle_http_401") || message.contains("oracle_http_403") ->
                resources.getString(R.string.error_key_refused)
            message.contains("oracle_http_429") -> resources.getString(R.string.error_too_soon)
            else -> resources.getString(R.string.error_generic, message.take(120))
        }
    }

    private fun startReply(text: String) {
        state = State.REPLYING
        replyText = text
        revealedPoints = 0
        replyStrokes = emptyList()
        replyFadeAnimator?.cancel()
        replyFadeAnimator = null
        replyAlpha = 255
        handler.removeCallbacks(thinkingRunnable)
        handler.removeCallbacks(revealRunnable)
        handler.post(revealRunnable)
        relayoutReply()
        invalidate()
    }

    /**
     * Re-traces the reply into pen paths off the main thread. Tracing a glyph is expensive the first
     * time and cached after, so re-laying out on each streamed delta stays cheap.
     */
    private fun relayoutReply() {
        val text = replyText
        if (text.isEmpty() || width == 0) return
        val margin = REPLY_MARGIN_DP * resources.displayMetrics.density
        val lineHeight = REPLY_TEXT_PX * resources.displayMetrics.density * REPLY_LINE_SPACING
        // A conjured page keeps the original handwriting where it was written, so the date and the
        // old reply are placed clear of it rather than across it.
        val top = replyTopOverride?.coerceAtMost(height - lineHeight * 3f) ?: margin

        layoutJob?.cancel()
        layoutJob = viewScope.launch {
            val laid = withContext(Dispatchers.Default) {
                replyScript.layout(
                    text = text,
                    left = margin,
                    top = top,
                    maxWidth = width - margin * 2,
                    lineHeight = lineHeight,
                )
            }
            replyStrokes = laid
            // Layout is async, so the reveal loop may have already run out of work and stopped —
            // an error or an index has no strokes to animate while it waits. Restart it now that
            // there is something to write.
            when (state) {
                State.REPLYING, State.FAILED, State.GUIDE -> {
                    handler.removeCallbacks(revealRunnable)
                    handler.post(revealRunnable)
                }
                State.RECALLING -> {
                    handler.removeCallbacks(recallRunnable)
                    handler.post(recallRunnable)
                }
                else -> Unit
            }
            invalidate()
        }
    }

    private fun startNewPage() {
        strokes.clear()
        currentStroke = null
        penPointerId = MotionEvent.INVALID_POINTER_ID
        recalledStrokes = emptyList()
        recalledRevealed = 0
        replyTopOverride = null
        replyStrokes = emptyList()
        revealedPoints = 0
        replyFadeAnimator?.cancel()
        replyFadeAnimator = null
        replyAlpha = 255
        replyText = ""
        replyComplete = false
        fadeAlpha = 255
        state = State.WRITING
    }

    /** Clears the failure notice but keeps the writing, so resting the pen sends the page again. */
    private fun resumeFailedPage() {
        handler.removeCallbacks(revealRunnable)
        layoutJob?.cancel()
        replyText = ""
        replyStrokes = emptyList()
        revealedPoints = 0
        replyComplete = false
        replyTopOverride = null
        state = State.WRITING
    }

    /** Drops the current page and any in-flight oracle request, returning to a blank writable page. */
    private fun abandonPage() {
        oracleJob?.cancel()
        oracleJob = null
        oracle.cancel()
        fadeAnimator?.cancel()
        handler.removeCallbacks(revealRunnable)
        handler.removeCallbacks(thinkingRunnable)
        handler.removeCallbacks(recallRunnable)
        handler.removeCallbacks(idleRunnable)
        layoutJob?.cancel()
        startNewPage()
    }

    /**
     * Renders the committed page for the oracle. Deliberately downscaled — riddle sends "a small
     * grayscale PNG", and a full-resolution page is a needlessly large upload for a model that
     * only has to read handwriting.
     */
    private fun renderPageBitmap(): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val scale = (MAX_PAGE_WIDTH_PX.toFloat() / w).coerceAtMost(1f)

        val bitmap = Bitmap.createBitmap(
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        drawStrokes(canvas, 255)
        return bitmap
    }

    private fun drawStrokes(canvas: Canvas, alpha: Int) {
        inkPaint.alpha = alpha
        for (stroke in strokes) {
            drawStroke(canvas, stroke)
        }
        currentStroke?.let { drawStroke(canvas, it) }
    }

    private fun drawStroke(canvas: Canvas, stroke: List<StrokePoint>) {
        for (i in 1 until stroke.size) {
            val a = stroke[i - 1]
            val b = stroke[i]
            inkPaint.strokeWidth = mapPressureToWidth(b.pressure)
            canvas.drawLine(a.x, a.y, b.x, b.y, inkPaint)
        }
    }

    private fun mapPressureToWidth(pressure: Float): Float {
        val density = resources.displayMetrics.density
        val effective = if (pressure <= 0f) 0.5f else pressure
        return (1.5f + effective * 5f) * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(ContextCompat.getColor(context, R.color.ink_background))
        if (vignettePaint.shader != null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignettePaint)
        }

        when (state) {
            State.WRITING -> drawStrokes(canvas, 255)
            State.THINKING -> {
                drawStrokes(canvas, fadeAlpha)
                val dots = ".".repeat(thinkingDots.coerceAtLeast(1))
                canvas.drawText(
                    dots,
                    width / 2f - 20f,
                    height - 80f * resources.displayMetrics.density,
                    thinkingPaint,
                )
            }
            State.REPLYING -> {
                replyPaint.alpha = replyAlpha
                drawReplyStrokes(canvas)
                replyPaint.alpha = 255
            }

            State.GUIDE -> drawReplyStrokes(canvas)

            // Your page, intact, with the reason written at the foot of it.
            State.FAILED -> {
                drawStrokes(canvas, 255)
                drawReplyStrokes(canvas)
            }

            State.RECALLING -> {
                var budget = recalledRevealed
                for (stroke in recalledStrokes) {
                    if (budget <= 0) break
                    val count = minOf(stroke.size, budget)
                    for (i in 1 until count) {
                        canvas.drawLine(
                            stroke[i - 1].first, stroke[i - 1].second,
                            stroke[i].first, stroke[i].second,
                            fadedInkPaint,
                        )
                    }
                    budget -= stroke.size
                }
                replyPaint.alpha = RECALL_ALPHA
                drawReplyStrokes(canvas)
                replyPaint.alpha = 255
            }
        }
    }

    /** Replays the traced reply as pen strokes, revealing only as many points as have been "written". */
    private fun drawReplyStrokes(canvas: Canvas) {
        var budget = revealedPoints
        for (stroke in replyStrokes) {
            if (budget <= 0) break
            val count = minOf(stroke.size, budget)
            for (i in 1 until count) {
                canvas.drawLine(stroke.xs[i - 1], stroke.ys[i - 1], stroke.xs[i], stroke.ys[i], replyPaint)
            }
            budget -= stroke.size
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        fadeAnimator?.cancel()
        oracle.cancel()
        viewJob.cancel()
    }

    companion object {
        private const val IDLE_MS = 2800L
        private const val FADE_MS = 700L
        private const val THINKING_TICK_MS = 450L

        /** Reply ink is revealed a few skeleton points per frame, so it reads as a moving pen. */
        private const val REVEAL_TICK_MS = 16L
        private const val POINTS_PER_TICK = 10

        /** How long a finished reply is left to be read before it sinks back into the paper. */
        private const val REPLY_HOLD_BASE_MS = 4_000L
        private const val REPLY_HOLD_PER_CHAR_MS = 45L
        private const val REPLY_HOLD_MAX_MS = 25_000L
        private const val REPLY_FADE_MS = 3_500L
        private const val REPLY_TEXT_PX = 30f
        private const val REPLY_LINE_SPACING = 1.5f
        private const val REPLY_MARGIN_DP = 56f

        /** Remembered ink is faint — a conjured page must never be mistaken for today's. */
        private const val RECALL_ALPHA = 90
        private const val RECALL_TEXT_GAP_DP = 28f
        private const val TAG = "DiaryPage"
        private const val ERASE_RADIUS_DP = 18f
        private const val MAX_PAGE_WIDTH_PX = 1024
    }
}
