package com.example.whatsapp_summarizer.feature.ask

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.whatsapp_summarizer.R
import com.example.whatsapp_summarizer.WhatsAppSummarizerApp
import com.example.whatsapp_summarizer.databinding.ActivityAskBinding
import com.example.whatsapp_summarizer.util.SecureStorage
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

/**
 * Asks a question across every captured group at once.
 */
class AskActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAskBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyRtlSettings()

        binding = ActivityAskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.ask_title)
            setDisplayHomeAsUpEnabled(true)
        }

        binding.buttonAsk.setOnClickListener { ask() }

        // Example chips fill the box, so the feature demonstrates its own range
        // instead of leaving the user staring at an empty field.
        listOf(binding.chipExample1, binding.chipExample2, binding.chipExample3)
            .forEach { chip: Chip ->
                chip.setOnClickListener {
                    binding.editQuestion.setText(chip.text)
                    binding.editQuestion.setSelection(chip.text.length)
                }
            }

        binding.buttonCopyAnswer.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.ask_title), binding.textAnswer.text)
            )
            Toast.makeText(this, getString(R.string.summary_copied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyRtlSettings() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        window.decorView.layoutDirection = if (prefs.getBoolean("rtl_mode", false)) {
            View.LAYOUT_DIRECTION_RTL
        } else {
            View.LAYOUT_DIRECTION_LTR
        }
    }

    private fun ask() {
        val question = binding.editQuestion.text?.toString()?.trim().orEmpty()
        if (question.isBlank()) {
            Toast.makeText(this, getString(R.string.ask_empty), Toast.LENGTH_SHORT).show()
            return
        }

        val apiKey = SecureStorage(this).getApiKey()
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.questions_no_key_body), Toast.LENGTH_LONG).show()
            return
        }

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val inHebrew = prefs.getBoolean("hebrew_language", false)
        val repository = (application as WhatsAppSummarizerApp).repository

        setLoading(true)
        lifecycleScope.launch {
            try {
                val pool = repository.getRecentGroupMessages(AskEngine.candidatePoolSize())
                val answer = AskEngine.ask(question, pool, apiKey, inHebrew)

                binding.textAnswer.text = answer.text
                binding.textSourceCount.text = resources.getQuantityString(
                    R.plurals.ask_source_count, answer.sourcesUsed, answer.sourcesUsed
                )
                binding.cardAnswer.isVisible = true
            } catch (e: Exception) {
                Toast.makeText(
                    this@AskActivity,
                    getString(R.string.ask_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.layoutLoading.isVisible = loading
        binding.buttonAsk.isEnabled = !loading
        if (loading) binding.cardAnswer.isVisible = false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            finish(); true
        } else {
            super.onOptionsItemSelected(item)
        }
    }
}
