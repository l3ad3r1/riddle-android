package com.riddleapp.diary

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var longPressAnchorX = 0f
    private var longPressAnchorY = 0f

    private lateinit var prefs: SecurePrefs
    private var appearance = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SecurePrefs(this)
        appearance = prefs.appearanceSignature()
        // The window shows through before the page draws; match it so there is no flash of the
        // other palette on launch or on return from settings.
        window.setBackgroundDrawable(
            ColorDrawable(Appearance.palette(this, prefs.darkMode).background)
        )
        setContentView(R.layout.activity_main)

        val settingsButton = findViewById<View>(R.id.settings_button)
        settingsButton.setOnClickListener { openSettings() }
        settingsButton.setOnTouchListener { view, event -> onSettingsTouch(view, event) }
    }

    /**
     * The gear is pen-only, so a stylus event falls straight through to the normal click. A finger
     * is swallowed — but held down it opens settings anyway, which is the escape hatch for a dead or
     * missing pen. Because the finger path is swallowed before the button's own long-press detector
     * runs, the timing is tracked here.
     */
    private fun onSettingsTouch(view: View, event: MotionEvent): Boolean {
        if (PenInput.hasPen(event)) {
            cancelLongPress()
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressAnchorX = event.rawX
                longPressAnchorY = event.rawY
                scheduleLongPress(view)
            }

            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                val drifted = abs(event.rawX - longPressAnchorX) > slop ||
                    abs(event.rawY - longPressAnchorY) > slop
                if (drifted) cancelLongPress()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPress()
        }
        return true
    }

    private fun scheduleLongPress(view: View) {
        cancelLongPress()
        val runnable = Runnable {
            longPressRunnable = null
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            openSettings()
        }
        longPressRunnable = runnable
        longPressHandler.postDelayed(runnable, LONG_PRESS_MS)
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onPause() {
        super.onPause()
        cancelLongPress()
    }

    /**
     * The page builds its palette and hand once, at construction, so a change made in settings only
     * takes effect by rebuilding it. Recreating discards the current page — acceptable, since you
     * cannot have been writing while you were on the settings screen.
     */
    override fun onResume() {
        super.onResume()
        val current = prefs.appearanceSignature()
        if (current != appearance) {
            appearance = current
            recreate()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    companion object {
        /** Deliberately longer than the system long-press, so it reads as intentional. */
        private const val LONG_PRESS_MS = 800L
    }
}
