# CLAUDE.md

Follow `E:\claude-projects\CLAUDE.md` for shared working rules. This file contains only project facts.

## Project

Phase 1 MVP Android shell inspired by [MaximeRivest/riddle](https://github.com/MaximeRivest/riddle)
(a reMarkable-only Rust app), retargeted at a normal Android tablet — specifically the
**TCL NXTPAPER** line (paper-textured LCD, T-Pen stylus with 4096 pressure levels via standard
Android `MotionEvent`, no root required). Package `com.riddleapp.diary`; AGP 9.1.1, Kotlin 2.2.10,
Gradle 9.6.1 (matched to the toolchain already working in `Octo-Jotter-repo` on this machine).

- Build: `./gradlew assembleDebug` (from this directory).
- No Compose, no Room, no Rust/NDK yet — plain Views + OkHttp + org.json, deliberately minimal.
- `SecurePrefs.kt` holds the oracle endpoint config (API key/base URL/model) in
  `EncryptedSharedPreferences`, editable from the in-app Settings screen — no `oracle.env` file.

## Phases (see conversation for full scope)

- **Phase 1 (this scaffold):** stylus capture → idle-commit → PNG → OpenAI-compatible
  `/chat/completions` call → reply shown as plain revealed cursive text. Working end-to-end loop.
- **Phase 1.5 (done):** streaming replies + an always-live pen. `OracleClient.ask` sends
  `stream: true` and parses SSE `data:` deltas, invoking `onChunk` per delta (falling back to parsing
  a plain JSON completion body for endpoints that ignore `stream`). `InkSurfaceView` flips to
  `REPLYING` on the first delta and keeps revealing as text arrives. Touching down during
  `THINKING`/`REPLYING` calls `abandonPage()`, which cancels the OkHttp call and the coroutine and
  begins the new stroke — pen input is never discarded, and a cancelled call is not reported as an
  error. Verified on device: first ink at 30 s instead of a blank page until 79 s.
- **Phase 1.6 (done):** pen-only page / palm rejection. `InkSurfaceView.onTouchEvent` consumes every
  event (so a resting palm never reaches anything else) but only draws for `TOOL_TYPE_STYLUS` /
  `TOOL_TYPE_ERASER`. The pen is tracked by **pointer id**, not pointer 0 — with a palm and the pen
  down together, pointer 0 is often the palm. Verified: `adb shell input touchscreen swipe` draws
  nothing, `adb shell input stylus swipe` draws. (`input` supports a `stylus` source — that is the
  only way to exercise this path from adb now.)
  The settings gear is pen-only too, via an `OnTouchListener` in `MainActivity` that returns `true`
  (swallow) for non-pen and `false` (fall through to the click) for the pen. Shared tool-type checks
  live in `PenInput.kt`. **Do not "fix" that listener by calling `performClick()` in the reject
  branch** — that fires the click and opens Settings on a finger tap, defeating the whole point.
  Escape hatch for a dead/missing pen: **long-press the gear with a finger for 800 ms**. The finger
  path is swallowed before the button's own long-press detector runs, so `MainActivity` times it
  itself (anchor + `scaledTouchSlop` drift check, cancelled on up/cancel/pause).

### TCL Smart Sidebar will eat your gestures

`com.tct.smart.sidebar` (TCL's "Writing Assist" edge panel) overlays the **whole screen**
(`[0,20][2200,1420]`) when triggered, and an edge press-and-hold triggers it. While it is up, the app
receives `ACTION_MOVE`s with **no `ACTION_DOWN`, then `ACTION_CANCEL`** — which looks exactly like a
broken touch listener and sent this session chasing a layout bug that did not exist. If touch
behaviour looks impossible, run `adb shell uiautomator dump` (with `MSYS_NO_PATHCONV=1` in Git Bash,
or the `/sdcard` path gets rewritten) and check which `package=` owns the screen before debugging the
app. Force-stopping and relaunching clears the stuck state.
- **Phase 2 (done, in Kotlin — no Rust/JNI needed):** replies are now *written*, not typed.
  `GlyphStrokes.kt` rasterizes each character from Dancing Script, thins it to a one-pixel skeleton
  (Zhang-Suen), and traces polylines normalized 0..1; `ReplyScript.kt` lays those out with word wrap;
  `InkSurfaceView` reveals them a few points per tick so a pen appears to move. Glyphs are cached and
  layout runs on `Dispatchers.Default` (first-pass tracing is expensive).
  **Attribution:** the thinning/tracing is adapted from `AndroidTypefaceReplyGlyphSource` in
  dc-daichao95/riddleInAndriod (MIT), a fork of MaximeRivest/riddle (MIT); `res/font/dancing_script.ttf`
  is the same OFL font riddle uses, license in `OFL.txt`. Keep both credits if this file is rewritten.
  **Gotcha:** a period/apostrophe thins to a *single pixel*. `tracePaths` originally dropped paths
  shorter than 2 points, which silently swallowed every period — one-point paths are now emitted as a
  hair-length round-capped stroke. Any change there must keep punctuation visible.
- **Phase 3a (done):** `MemoryStore.kt` — every **successful** page is kept under
  `filesDir/memories` as `<epochMillis>.png` (the committed page) + `<epochMillis>.json`
  (`id`, `reply`, and raw `strokes` as `[[x,y,pressure],…]`), pruned to the last 400 pages like
  riddle. The last `CONTEXT_PAGES` (3) ride along with each request as prior user/assistant turns, so
  the diary follows the conversation. A failed or timed-out page is **not** saved. Strokes are stored
  though nothing replays them yet — that is deliberate, so Phase 3b needs no migration.
  Settings has a "Remember past pages" toggle (riddle's `RIDDLE_MEMORY=off`) and a confirmed
  "Forget everything". Memories never leave the tablet except as the page images sent to the oracle.
- **Phase 3a+ (done):** on-device handwriting recognition. `HandwritingRecognizer.kt` (ML Kit digital
  ink, `en-US`) reads the committed strokes into text, run **concurrently with the oracle call** so it
  adds no wall-clock time. The transcript is stored on the page and, on later requests, that page is
  sent as a line of text instead of its image — measured at **~230 B vs ~13 KB, roughly 55× cheaper**,
  which is why `CONTEXT_PAGES` went 3 → 10. Pages without a transcript (stored before this existed, or
  where recognition failed) still fall back to the image, so old memories keep working.
  Approach adapted from `MlKitHandwritingRecognizer` in dc-daichao95/riddleInAndriod (MIT).
  **The transcript also rides with the *current* page** (`today=image+text` in the request log), not
  just past ones. This matters: on real cursive the vision model read "beans in the garden" as
  *"Beams"*, while the on-device recognizer — which follows the pen's actual path rather than pixels —
  got it right. With the transcript attached the model replied "so they are beans, not beams". The
  image is still sent, so drawings and anything unrecognized remain visible. Don't drop either half.
  The `en-US` model downloads on first use (~6 s observed) and is cached; the download is serialized
  and `NonCancellable` so two pages cannot start duplicate downloads.
  **APK cost:** ML Kit ships native libs for every ABI and took the APK 5 MB → 48.8 MB. `abiFilters`
  is pinned to `arm64-v8a` (the tablet), bringing it to **17 MB** — remove that filter and the APK
  quadruples again, but 32-bit devices will not install without it.
- **Phase 3b (done):** the recall gestures. `Recall.kt` matches the *transcript* — so a recall is
  answered **entirely on the tablet, with no request and no network**, and the page is not stored
  (asking to see the past is not a new page). "what do you remember" → a handwritten index;
  "show me what I wrote about X" → the best-matching page conjured back: its strokes rewritten in
  your own hand first, then the date and old reply, all at `RECALL_ALPHA`. Any pen touch returns to
  today's page. Recognition now runs **before** the oracle call (it has to, to detect a recall).
  **Testing note:** recall cannot be exercised from adb — `input stylus swipe` transcribes as noise
  (e.g. `"1-"`), never a phrase. The parsing/search rules are pinned in `app/src/test/.../RecallTest.kt`
  (`./gradlew testDebugUnitTest`); the on-device gesture needs a human with the T-Pen.
  Recalled strokes are drawn at their **original page coordinates** — fine on one tablet in one
  orientation, but they are not normalized, so they would misplace on a different screen size.
  Search drops **stopwords**: without that, "show me what I wrote about the sea" matched any page
  containing "the", so a subject you never wrote about returned an arbitrary page.
  Recall is deliberately **only** the explicit forms ("show me what I wrote about…"). A question like
  "what did I plant in the garden?" is left to the oracle, which answers it conversationally from
  memory — a better response than replaying the page. Don't widen the patterns to swallow questions.

## Verified on device (2026-07-21)

Installed and exercised end-to-end on a **TCL 9469X (Bellona_WF_GL), Android 15, arm64-v8a**.
Confirmed working: T-Pen input arrives as `TOOL_TYPE_STYLUS` in `InkSurfaceView`, idle-commit fires,
page rasterizes, vision call returns, reply reveals in the diary's hand. The model's reply referenced
the actual number of strokes on the page, confirming the image is really being read.

Two things learned the hard way — keep them in mind before "fixing" the timeouts back down:

- A free-tier vision endpoint answered in **~79 s**. The original 60 s `readTimeout` aborted a request
  that would have succeeded, surfacing as "The diary could not answer: timeout". Timeouts are now
  15 s connect / 30 s write / 180 s read. A slow endpoint is the norm here, not an anomaly — and the
  long read timeout is safe because the pen can interrupt at any moment.
- Endpoint latency can be **wildly variable**: measured 30 s, 79 s, and >90 s (timeout) for the same
  prompt on consecutive runs. It is a queue, not a steady round-trip. Don't tune timeouts against a
  single sample, and don't conclude an endpoint "doesn't stream" from one silent run — it may well.

`OracleClient` logs to tag **`DiaryOracle`** (endpoint, model, PNG/payload size, response code,
elapsed ms, exception class) — never the API key. `adb logcat -s DiaryOracle` is the fastest way to
diagnose a failed page.

## Theme

Aged parchment (`ink_background`) with a radial vignette drawn in `InkSurfaceView.onSizeChanged`;
your hand is iron-gall quill ink (`ink_stroke`), the diary answers in **deep green** (`ink_reply`) so
the two hands are never confused. Persona lives in `OracleClient.personaPrompt` — courteous, softly
persuasive, curious, with a faint chill; it is written in original wording and is explicitly told not
to claim to be any character from a book or film. This is a personal fan-flavoured app: keep it
evocative, don't brand it with someone else's trademarks.

### The reply is written, then sinks away

A finished reply is held to be read and then fades out, leaving a blank page — riddle's "answers in a
flowing hand, stroke by stroke, then fades away". `scheduleReplyFade` in `InkSurfaceView` holds for
`REPLY_HOLD_BASE_MS + length * REPLY_HOLD_PER_CHAR_MS` (capped), then animates `replyAlpha` to zero
and calls `startNewPage`.

The hold is **deliberately generous** — the timings here were raised once already after they read as
too quick on the tablet. The cost is asymmetric: the pen clears the page the instant you want to
write, so an over-long hold costs nothing, while a short one takes words away mid-sentence. Note the
**cap** silently governs long replies; raising the per-character figure alone does nothing once it
binds.

Only `REPLYING` fades. The **? guide** and a **failure notice** stay until the pen dismisses them —
reference and recovery must not evaporate while being read or retried — and recall follows riddle in
waiting for the pen.

### The ? guide

Drawing a large **?** alone on a page summons `State.GUIDE` — the diary's own instructions, written
in its hand, answered on-device with no request and not stored. Detection lives in `Recall.parse`
and is checked **before** `TextMatch.normalize`, which strips punctuation and would erase a lone "?".
Recognition frequently reads one big mark as `"??"` or ranks `"T"`/`"7"` above it, which is exactly
why all candidates are considered rather than the top one.

Two traps, both hit on the way in:
- A **literal newline in `strings.xml` collapses to a space**. `guide_text` must use `\n` escapes, and
  `ReplyScript` carries a `NEWLINE` sentinel token so a break survives layout.
- The guide reveals at **4× the reply rate**; at reply speed the full page took ~90 s to write, which
  is intolerable for something you summoned to read.

### A failed page is never lost

`State.FAILED` keeps your strokes at full strength and writes the reason at the **foot** of the page
(`replyTopOverride`), clear of the writing. Touching the pen calls `resumeFailedPage()` — which clears
the notice but **keeps the strokes** — so resting the pen re-sends the same page. This matters because
the configured endpoint routinely takes 30–180 s and sometimes times out; discarding the page after
that wait was the worst thing the app did. `describeError` gives the common causes plain words
(offline / timeout / 401 / 429); the raw exception stays in the `DiaryOracle` log.
Note `resumeFailedPage` is the one state transition that must *not* call `abandonPage()`.

### Bug worth remembering: the reveal loop can die before layout arrives

Reply layout is async (`Dispatchers.Default`), but `revealRunnable`/`recallRunnable` stop as soon as
they run out of points *and* `replyComplete` is true. On the **error path** and the **"what do you
remember" index**, `replyComplete` is already true when the animation starts and `replyStrokes` is
still empty, so the loop returned immediately and the page rendered **blank forever**. The streaming
success path masked it (`replyComplete` is false there until the stream ends). Fix: `relayoutReply`
re-posts the appropriate runnable once strokes exist. Don't remove that restart.

## Known simplifications vs. riddle

- No true e-ink — NXTPAPER is a matte LCD, so "the page drinks your ink" is a software fade
  (`ValueAnimator`, `InkSurfaceView.commitPage()`), not a hardware effect.
- No takeover mode / no root — this is a normal signed APK. Screen pinning is the closest analogue
  to riddle's 5-finger-tap exit, not yet implemented.
- Reply strokes are uniform width; riddle varies the pen. Your own ink is pressure-varying already.
- No page persistence — the page lives only in `InkSurfaceView`'s fields, so an activity recreation
  (rotation, process death) loses the page and any in-flight reply. Fine for Phase 1, wrong for daily use.
- Persona prompt in `OracleClient.kt` is intentionally generic ("a diary"), not literally
  branded as riddle's Tom Riddle persona — customize `personaPrompt` to taste.
