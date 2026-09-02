package com.example.whatsapp_summarizer.feature.digest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.example.whatsapp_summarizer.R
import com.example.whatsapp_summarizer.databinding.ActivityDigestBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows the most recent daily digest, so dismissing the notification does not
 * throw away the day's summary.
 */
class DigestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDigestBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyRtlSettings()

        binding = ActivityDigestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.digest_title)
            setDisplayHomeAsUpEnabled(true)
        }

        val settings = DigestSettings(this)
        val digest = settings.lastDigest

        if (digest.isBlank()) {
            binding.emptyState.isVisible = true
            binding.cardDigest.isVisible = false
        } else {
            binding.emptyState.isVisible = false
            binding.cardDigest.isVisible = true
            binding.textDigest.text = digest
            binding.textDigestWhen.text = SimpleDateFormat("EEEE d MMM, HH:mm", Locale.getDefault())
                .format(Date(settings.lastDigestAt))
        }

        binding.buttonCopyDigest.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.digest_title), binding.textDigest.text)
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            finish(); true
        } else {
            super.onOptionsItemSelected(item)
        }
    }
}
