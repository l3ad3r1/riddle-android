# The Diary — an Android port of riddle

Write on the page with a stylus. Rest your pen, and the page answers you in its own hand,
stroke by stroke, in green ink on parchment.

This is an Android application inspired by **[MaximeRivest/riddle](https://github.com/MaximeRivest/riddle)**,
which is written in Rust and runs only on the reMarkable Paper Pro. Nothing of that hardware is
required here: this is an ordinary signed APK, no root, built and used on a **TCL NXTPAPER** tablet
with its T-Pen.

## What it does

- **Pen only.** Fingers and resting palms are ignored everywhere, including the settings control.
  The pen is tracked by pointer id, because when you write your palm usually lands first.
- **Rest the pen and the page commits.** Your ink fades, the page is read, and the reply is written
  back — not typed. Each character is rasterized from Dancing Script, thinned to a one-pixel
  skeleton (Zhang-Suen) and traced into pen paths, then drawn point by point.
- **Streamed.** Ink begins appearing as soon as the model starts answering, rather than after it
  finishes.
- **The pen is never ignored.** Writing while the diary is thinking cancels that request and starts
  your new page immediately.
- **It remembers.** Finished pages are kept on the tablet — the image, the reply, and your raw
  strokes. Recent pages ride along with each request, so the diary follows the conversation.
- **It reads your handwriting on device** (ML Kit digital ink). A transcribed page travels as a line
  of text instead of a ~14 KB image — measured at roughly 55× cheaper — and the transcript is sent
  with the current page too, which fixes misreadings the vision model makes from pixels alone.
- **Recall, answered offline.** Write *"what do you remember?"* for an index, or
  *"show me what I wrote about the garden"* to conjure that page back: your own strokes rewritten in
  faded ink. Matched on device, so it needs no network and answers instantly.
- **A guide.** Draw a large **?** and the diary explains itself.
- **A failed page is never lost.** If the model cannot answer, your writing stays and the reason is
  written at the foot of the page; rest the pen to send it again.
- **It fades.** A finished reply is left to be read, then sinks back into the paper, leaving a blank
  page — or stays until you write again, if you prefer.

## Settings

Press and hold the mark in the top-right corner. It is pen-only like the page, so a finger long-press
is the way in if your stylus is not to hand.

| | |
|---|---|
| **Oracle** | API key, base URL, model. The key is held in `EncryptedSharedPreferences`. |
| **Appearance** | The hand the diary writes in, dark paper, the size of its hand, the thickness of your ink. |
| **Writing** | How long the pen must rest before a page is sent, how fast the diary writes, how long a reply lingers. |
| **Memory** | Remember past pages on or off, how many are kept, and *Forget everything*. |

## Requirements

- Android 8.0 (API 26) or newer, **arm64-v8a**
- A stylus that reports as one (`TOOL_TYPE_STYLUS`)
- Any OpenAI-compatible vision endpoint

## Install

Take the APK from the [latest release](https://github.com/l3ad3r1/riddle-android/releases/latest),
allow installation from unknown sources, and open it. Then press and hold the mark in the top-right
corner and set an API key, base URL, and a vision-capable model.

The APK is arm64-only: ML Kit ships native libraries for every ABI and the filter keeps the build at
~17 MB instead of ~49 MB. Remove `abiFilters` in `app/build.gradle.kts` if you need 32-bit devices.

## Build

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # recall parsing, fuzzy matching and search
```

Then open the app, press and hold the mark in the top-right corner, and set an API key, base URL
(e.g. `https://api.openai.com/v1`) and a **vision-capable** model. The key is held in
`EncryptedSharedPreferences` and never written to logs.

## Privacy

Pages live only on the tablet, under the app's private storage. Nothing is logged of what you write:
not the strokes, not the transcript, not the reply. The only thing that leaves the device is the page
image — and its transcript — sent to the endpoint **you** configure. Turn memory off in settings and
nothing is stored or carried forward; "Forget everything" deletes it all.

## Credits

- **[MaximeRivest/riddle](https://github.com/MaximeRivest/riddle)** (MIT) — the original, and the
  source of every idea here: the ink that drinks, the handwriting reply, the memory, the gestures.
- **[dc-daichao95/riddleInAndriod](https://github.com/dc-daichao95/riddleInAndriod)** (MIT) — an
  independent Android port. The glyph thinning/tracing in `GlyphStrokes.kt` and the ML Kit approach
  in `HandwritingRecognizer.kt` are adapted from it.
- **[Dancing Script](https://github.com/googlefonts/DancingScript)** (SIL OFL 1.1, see `OFL.txt`) —
  the reply hand, the same font riddle uses.

MIT licensed; see `LICENSE`. Not affiliated with reMarkable AS or TCL.
