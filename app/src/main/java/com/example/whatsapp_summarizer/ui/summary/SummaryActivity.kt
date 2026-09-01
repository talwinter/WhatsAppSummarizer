package com.example.whatsapp_summarizer.ui.summary

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.example.whatsapp_summarizer.R
import com.example.whatsapp_summarizer.databinding.ActivitySummaryBinding
import com.example.whatsapp_summarizer.ui.settings.SettingsActivity
import com.example.whatsapp_summarizer.util.SecureStorage
import java.util.*

class SummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySummaryBinding
    private val viewModel: SummaryViewModel by viewModels()
    private lateinit var secureStorage: SecureStorage
    private var chatName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Apply RTL setting before setting content view
        applyRtlSettings()
        
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chatName = intent.getStringExtra("CHAT_NAME") ?: run {
            Toast.makeText(this, "Error: No chat selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = chatName
            subtitle = getString(R.string.summary_label)
            setDisplayHomeAsUpEnabled(true)
        }

        secureStorage = SecureStorage(this)

        setupUI()
        setupListeners()
        observeViewModel()
    }

    private fun applyRtlSettings() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val rtlEnabled = prefs.getBoolean("rtl_mode", false)
        if (rtlEnabled) {
            window.decorView.layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
        } else {
            window.decorView.layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR
        }
    }

    private fun setupUI() {
        val hasKey = secureStorage.hasApiKey()
        binding.cardNoApiKey.isVisible = !hasKey
        binding.buttonGenerateSummary.isVisible = hasKey

        // Default to the whole day, matching the old radio-button default.
        if (binding.toggleTimeRange.checkedButtonId == View.NO_ID) {
            binding.toggleTimeRange.check(binding.buttonWholeDay.id)
        }
    }

    private fun setupListeners() {
        // Time range segmented control
        binding.toggleTimeRange.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                binding.layoutCustomRange.isVisible = checkedId == binding.buttonCustomRange.id
            }
        }

        // Copy the generated summary
        binding.buttonCopySummary.setOnClickListener {
            val clipboard =
                getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText(chatName, binding.textSummary.text)
            )
            Toast.makeText(this, getString(R.string.summary_copied), Toast.LENGTH_SHORT).show()
        }

        // Time Pickers
        binding.editTimeFrom.setOnClickListener {
            showTimePicker { hour, minute ->
                binding.editTimeFrom.setText(String.format("%02d:%02d", hour, minute))
            }
        }

        binding.editTimeTo.setOnClickListener {
            showTimePicker { hour, minute ->
                binding.editTimeTo.setText(String.format("%02d:%02d", hour, minute))
            }
        }

        // Generate Summary
        binding.buttonGenerateSummary.setOnClickListener {
            generateSummary()
        }

        binding.buttonGoToSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun showTimePicker(onTimeSet: (Int, Int) -> Unit) {
        val calendar = Calendar.getInstance()
        TimePickerDialog(
            this,
            { _, hour, minute ->
                onTimeSet(hour, minute)
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true // 24-hour format
        ).show()
    }

    private fun generateSummary() {
        val apiKey = secureStorage.getApiKey()
        if (apiKey == null) {
            Toast.makeText(this, "API key not found. Please configure in Settings.", Toast.LENGTH_LONG).show()
            return
        }

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val inHebrew = prefs.getBoolean("hebrew_language", false)

        // Calculate time range
        val (startTime, endTime) = when (binding.toggleTimeRange.checkedButtonId) {
            binding.buttonCustomRange.id -> {
                val fromText = binding.editTimeFrom.text.toString()
                val toText = binding.editTimeTo.text.toString()
                
                if (fromText.isBlank() || toText.isBlank()) {
                    Toast.makeText(this, "Please enter both times", Toast.LENGTH_SHORT).show()
                    return
                }
                
                parseTimeRange(fromText, toText)
            }
            else -> Pair(null, null) // Whole day
        }

        viewModel.generateSummary(
            chatName = chatName,
            apiKey = apiKey,
            inHebrew = inHebrew,
            startTime = startTime,
            endTime = endTime
        )
    }

    private fun parseTimeRange(fromTime: String, toTime: String): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        val today = calendar.get(Calendar.DAY_OF_YEAR)
        val year = calendar.get(Calendar.YEAR)

        // Parse from time
        val fromParts = fromTime.split(":")
        val fromHour = fromParts[0].toInt()
        val fromMinute = fromParts[1].toInt()

        calendar.set(Calendar.HOUR_OF_DAY, fromHour)
        calendar.set(Calendar.MINUTE, fromMinute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis

        // Parse to time
        val toParts = toTime.split(":")
        val toHour = toParts[0].toInt()
        val toMinute = toParts[1].toInt()

        calendar.set(Calendar.HOUR_OF_DAY, toHour)
        calendar.set(Calendar.MINUTE, toMinute)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        val endTime = calendar.timeInMillis

        return Pair(startTime, endTime)
    }

    private fun observeViewModel() {
        viewModel.summary.observe(this) { summary ->
            binding.textSummary.text = summary
            binding.buttonCopySummary.isVisible = !summary.isNullOrBlank()
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.layoutLoading.isVisible = isLoading
            binding.cardSummary.isVisible = !isLoading
            binding.buttonGenerateSummary.isEnabled = !isLoading
        }

        viewModel.error.observe(this) { error ->
            error?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupUI()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
