package com.example.whatsapp_summarizer.ui.summary

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
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
            title = getString(R.string.summarize) + ": " + chatName
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
        if (!secureStorage.hasApiKey()) {
            binding.cardNoApiKey.isVisible = true
            binding.buttonGenerateSummary.isVisible = false
            binding.textSummary.text = "No API key configured. Please set your OpenAI API key in Settings first."
        } else {
            binding.cardNoApiKey.isVisible = false
            binding.buttonGenerateSummary.isVisible = true
        }
    }

    private fun setupListeners() {
        // Time Range Selection
        binding.radioGroupTimeRange.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                binding.radioWholeDay.id -> {
                    binding.layoutCustomRange.isVisible = false
                }
                binding.radioCustomRange.id -> {
                    binding.layoutCustomRange.isVisible = true
                }
            }
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
        val useLocalModel = prefs.getBoolean("use_local_model", false)

        // Calculate time range
        val (startTime, endTime) = when (binding.radioGroupTimeRange.checkedRadioButtonId) {
            binding.radioCustomRange.id -> {
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
            useLocalModel = useLocalModel,
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
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.isVisible = isLoading
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
