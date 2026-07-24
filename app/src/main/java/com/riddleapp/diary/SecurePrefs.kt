package com.riddleapp.diary

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Encrypted on-device storage for the oracle endpoint config — the Android analogue of riddle's `oracle.env`. */
class SecurePrefs(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "oracle_config",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /** When off, nothing is stored and no past pages are sent — riddle's `RIDDLE_MEMORY=off`. */
    var memoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEMORY, true)
        set(value) = prefs.edit().putBoolean(KEY_MEMORY, value).apply()

    /**
     * The hand the diary writes in. Until chosen, [fallback] decides — the caller passes whichever
     * hand is preferred on this build, so adding the setting does not silently change the page.
     */
    fun hand(fallback: Appearance.Hand): Appearance.Hand =
        prefs.getString(KEY_HAND, null)?.let { Appearance.Hand.from(it) } ?: fallback

    fun setHand(value: Appearance.Hand) = prefs.edit().putString(KEY_HAND, value.key).apply()

    /** Dark paper for writing at night. */
    var darkMode: Boolean
        get() = prefs.getBoolean(KEY_DARK, false)
        set(value) = prefs.edit().putBoolean(KEY_DARK, value).apply()

    /** Size of the diary's hand, in density-independent pixels. */
    var replyTextSize: Int
        get() = prefs.getInt(KEY_REPLY_SIZE, DEFAULT_REPLY_SIZE).coerceIn(MIN_REPLY_SIZE, MAX_REPLY_SIZE)
        set(value) = prefs.edit().putInt(KEY_REPLY_SIZE, value.coerceIn(MIN_REPLY_SIZE, MAX_REPLY_SIZE)).apply()

    /** Thickness of your own ink, as a percentage of the natural pressure-mapped width. */
    var inkThickness: Int
        get() = prefs.getInt(KEY_INK_THICKNESS, 100).coerceIn(MIN_THICKNESS, MAX_THICKNESS)
        set(value) = prefs.edit().putInt(KEY_INK_THICKNESS, value.coerceIn(MIN_THICKNESS, MAX_THICKNESS)).apply()

    /** How long the pen must rest before the page is sent, in milliseconds. */
    var idlePauseMs: Long
        get() = prefs.getInt(KEY_IDLE_PAUSE, DEFAULT_IDLE_PAUSE).coerceIn(MIN_IDLE_PAUSE, MAX_IDLE_PAUSE).toLong()
        set(value) = prefs.edit()
            .putInt(KEY_IDLE_PAUSE, value.toInt().coerceIn(MIN_IDLE_PAUSE, MAX_IDLE_PAUSE)).apply()

    /** How long a finished reply lingers before fading, as a percentage of the natural hold. */
    var lingerPercent: Int
        get() = prefs.getInt(KEY_LINGER, 100)
        set(value) = prefs.edit().putInt(KEY_LINGER, value).apply()

    /** How fast the diary writes, as a percentage of the natural pace. */
    var writingSpeedPercent: Int
        get() = prefs.getInt(KEY_SPEED, 100)
        set(value) = prefs.edit().putInt(KEY_SPEED, value).apply()

    /**
     * Settings the page bakes in at construction, so the view knows when it must be rebuilt. Values
     * read afresh each time a page is written — the pause, the linger, the pace — are not here.
     */
    fun appearanceSignature(): String =
        "${prefs.getString(KEY_HAND, "")}|$darkMode|$replyTextSize|$inkThickness"

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_MEMORY = "memory_enabled"
        private const val KEY_HAND = "reply_hand"
        private const val KEY_DARK = "dark_mode"
        private const val KEY_REPLY_SIZE = "reply_size"
        private const val KEY_INK_THICKNESS = "ink_thickness"
        private const val KEY_IDLE_PAUSE = "idle_pause_ms"
        private const val KEY_LINGER = "linger_percent"
        private const val KEY_SPEED = "writing_speed_percent"

        const val DEFAULT_REPLY_SIZE = 30
        const val MIN_REPLY_SIZE = 16
        const val MAX_REPLY_SIZE = 56

        const val MIN_THICKNESS = 50
        const val MAX_THICKNESS = 250

        /** riddle rests the pen for 2.8 s before committing a page. */
        const val DEFAULT_IDLE_PAUSE = 2_800
        const val MIN_IDLE_PAUSE = 1_000
        const val MAX_IDLE_PAUSE = 10_000
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}
