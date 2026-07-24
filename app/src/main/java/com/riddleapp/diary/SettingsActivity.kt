package com.riddleapp.diary

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = SecurePrefs(this)
        val memory = MemoryStore(this)
        val apiKeyField = findViewById<EditText>(R.id.input_api_key)
        val baseUrlField = findViewById<EditText>(R.id.input_base_url)
        val modelField = findViewById<EditText>(R.id.input_model)
        val memoryCheck = findViewById<CheckBox>(R.id.check_memory)
        val memoryStatus = findViewById<TextView>(R.id.text_memory_status)
        val forgetButton = findViewById<Button>(R.id.button_forget)
        val handSpinner = findViewById<Spinner>(R.id.spinner_hand)
        val darkCheck = findViewById<CheckBox>(R.id.check_dark)

        apiKeyField.setText(prefs.apiKey)
        baseUrlField.setText(prefs.baseUrl)
        modelField.setText(prefs.model)
        memoryCheck.isChecked = prefs.memoryEnabled
        darkCheck.isChecked = prefs.darkMode

        // Only hands actually present in this build are offered.
        val hands = Appearance.availableHands(this)
        handSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            hands.map { it.label },
        )
        handSpinner.setSelection(hands.indexOf(prefs.hand(Appearance.defaultHand(this))).coerceAtLeast(0))

        // Sliders are stored as offsets from their minimum, since SeekBar counts from zero.
        val replySizeSeek = findViewById<SeekBar>(R.id.seek_reply_size)
        val replySizeLabel = findViewById<TextView>(R.id.label_reply_size)
        replySizeSeek.max = SecurePrefs.MAX_REPLY_SIZE - SecurePrefs.MIN_REPLY_SIZE
        replySizeSeek.progress = prefs.replyTextSize - SecurePrefs.MIN_REPLY_SIZE
        fun replySizeValue() = replySizeSeek.progress + SecurePrefs.MIN_REPLY_SIZE

        val thicknessSeek = findViewById<SeekBar>(R.id.seek_ink_thickness)
        val thicknessLabel = findViewById<TextView>(R.id.label_ink_thickness)
        thicknessSeek.max = SecurePrefs.MAX_THICKNESS - SecurePrefs.MIN_THICKNESS
        thicknessSeek.progress = prefs.inkThickness - SecurePrefs.MIN_THICKNESS
        fun thicknessValue() = thicknessSeek.progress + SecurePrefs.MIN_THICKNESS

        val pauseSeek = findViewById<SeekBar>(R.id.seek_idle_pause)
        val pauseLabel = findViewById<TextView>(R.id.label_idle_pause)
        pauseSeek.max = (SecurePrefs.MAX_IDLE_PAUSE - SecurePrefs.MIN_IDLE_PAUSE) / PAUSE_STEP_MS
        pauseSeek.progress = (prefs.idlePauseMs.toInt() - SecurePrefs.MIN_IDLE_PAUSE) / PAUSE_STEP_MS
        fun pauseValue() = pauseSeek.progress * PAUSE_STEP_MS + SecurePrefs.MIN_IDLE_PAUSE

        fun refreshLabels() {
            replySizeLabel.text = getString(R.string.reply_size, replySizeValue())
            thicknessLabel.text = getString(R.string.ink_thickness, thicknessValue())
            pauseLabel.text = getString(R.string.idle_pause, "%.1f".format(pauseValue() / 1000f))
        }
        refreshLabels()
        val watcher = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) = refreshLabels()
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        }
        replySizeSeek.setOnSeekBarChangeListener(watcher)
        thicknessSeek.setOnSeekBarChangeListener(watcher)
        pauseSeek.setOnSeekBarChangeListener(watcher)

        val paceSpinner = findViewById<Spinner>(R.id.spinner_pace)
        paceSpinner.adapter = ArrayAdapter.createFromResource(
            this, R.array.pace_labels, android.R.layout.simple_spinner_dropdown_item
        )
        paceSpinner.setSelection(PACE_VALUES.indexOf(prefs.writingSpeedPercent).coerceAtLeast(1))

        val lingerSpinner = findViewById<Spinner>(R.id.spinner_linger)
        lingerSpinner.adapter = ArrayAdapter.createFromResource(
            this, R.array.linger_labels, android.R.layout.simple_spinner_dropdown_item
        )
        lingerSpinner.setSelection(LINGER_VALUES.indexOf(prefs.lingerPercent).coerceAtLeast(1))

        fun refreshMemoryStatus() {
            val remembered = memory.count()
            memoryStatus.text = if (memoryCheck.isChecked) {
                getString(R.string.memory_count, remembered)
            } else {
                getString(R.string.memory_off)
            }
            forgetButton.isEnabled = remembered > 0
        }
        refreshMemoryStatus()
        memoryCheck.setOnCheckedChangeListener { _, _ -> refreshMemoryStatus() }

        forgetButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.forget_confirm_title)
                .setMessage(R.string.forget_confirm_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.forget_confirm_yes) { _, _ ->
                    memory.forget()
                    refreshMemoryStatus()
                }
                .show()
        }

        findViewById<Button>(R.id.button_save).setOnClickListener {
            prefs.apiKey = apiKeyField.text.toString().trim()
            prefs.baseUrl = baseUrlField.text.toString().trim().ifEmpty { SecurePrefs.DEFAULT_BASE_URL }
            prefs.model = modelField.text.toString().trim().ifEmpty { SecurePrefs.DEFAULT_MODEL }
            prefs.memoryEnabled = memoryCheck.isChecked
            prefs.setHand(hands.getOrElse(handSpinner.selectedItemPosition) { Appearance.defaultHand(this) })
            prefs.darkMode = darkCheck.isChecked
            prefs.replyTextSize = replySizeValue()
            prefs.inkThickness = thicknessValue()
            prefs.idlePauseMs = pauseValue().toLong()
            prefs.writingSpeedPercent = PACE_VALUES.getOrElse(paceSpinner.selectedItemPosition) { 100 }
            prefs.lingerPercent = LINGER_VALUES.getOrElse(lingerSpinner.selectedItemPosition) { 100 }
            finish()
        }
    }

    private companion object {
        /**
         * The pause slider steps in fifths of a second. It must divide the default (2.8 s) exactly,
         * or simply opening settings and saving would quietly move the pause to the nearest step.
         */
        const val PAUSE_STEP_MS = 200

        /** Percentages behind `pace_labels`, in the same order. */
        val PACE_VALUES = listOf(60, 100, 180)

        /** Percentages behind `linger_labels`; zero means the reply never fades on its own. */
        val LINGER_VALUES = listOf(50, 100, 250, 0)
    }
}
