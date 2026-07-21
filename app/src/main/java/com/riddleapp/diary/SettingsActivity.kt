package com.riddleapp.diary

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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

        apiKeyField.setText(prefs.apiKey)
        baseUrlField.setText(prefs.baseUrl)
        modelField.setText(prefs.model)
        memoryCheck.isChecked = prefs.memoryEnabled

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
            finish()
        }
    }
}
