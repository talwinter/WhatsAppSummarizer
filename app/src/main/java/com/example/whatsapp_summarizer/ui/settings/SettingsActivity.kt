package com.example.whatsapp_summarizer.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.whatsapp_summarizer.R
import com.example.whatsapp_summarizer.databinding.ActivitySettingsBinding
import com.example.whatsapp_summarizer.util.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var secureStorage: SecureStorage
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Apply RTL setting before setting content view
        applyRtlSettings()
        
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.settings)
            setDisplayHomeAsUpEnabled(true)
        }

        secureStorage = SecureStorage(this)

        setupUI()
        setupListeners()
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
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        
        // API Key Status
        updateApiKeyStatus()
        if (secureStorage.hasApiKey()) {
            binding.editApiKey.setText("••••••••••••••••••••••••••")
        }

        // Groups-only capture (default on)
        binding.switchGroupsOnly.isChecked = prefs.getBoolean("groups_only", true)

        // RTL Toggle
        binding.switchRtl.isChecked = prefs.getBoolean("rtl_mode", false)

        // Hebrew Language Toggle
        binding.switchHebrew.isChecked = prefs.getBoolean("hebrew_language", false)

        // GreenAPI Settings
        val greenIdInstance = prefs.getString("green_id_instance", "")
        val greenApiToken = prefs.getString("green_api_token", "")
        binding.editGreenIdInstance.setText(greenIdInstance)
        binding.editGreenApiToken.setText(greenApiToken)
    }

    private fun setupListeners() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        // Save API Key
        binding.buttonSaveApiKey.setOnClickListener {
            val apiKey = binding.editApiKey.text.toString().trim()
            
            if (apiKey.isBlank() || apiKey == "••••••••••••••••••••••••••") {
                Toast.makeText(this, "Please enter a valid API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isValidApiKey(apiKey)) {
                Toast.makeText(this, "Invalid API key format. Should start with 'sk-' for OpenAI", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            secureStorage.storeApiKey(apiKey)
            Toast.makeText(this, "API Key saved securely", Toast.LENGTH_SHORT).show()
            updateApiKeyStatus()
            binding.editApiKey.setText("••••••••••••••••••••••••••")
        }

        // Clear API Key
        binding.buttonClearApiKey.setOnClickListener {
            secureStorage.clearApiKey()
            binding.editApiKey.text?.clear()
            Toast.makeText(this, "API Key removed", Toast.LENGTH_SHORT).show()
            updateApiKeyStatus()
        }

        // Groups-only capture
        binding.switchGroupsOnly.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("groups_only", isChecked).apply()
            val msg = if (isChecked) {
                "Only group chats will be captured"
            } else {
                "Personal conversations will be captured too"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Purge personal chats captured before group detection was fixed
        binding.buttonPurgePersonal.setOnClickListener {
            confirmPurgePersonal()
        }

        // RTL Toggle
        binding.switchRtl.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("rtl_mode", isChecked).apply()
            Toast.makeText(this, "RTL mode updated. Restart app to apply.", Toast.LENGTH_LONG).show()
        }

        // Hebrew Language Toggle
        binding.switchHebrew.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("hebrew_language", isChecked).apply()
            val msg = if (isChecked) "Hebrew language enabled" else "English language enabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // GreenAPI Save
        binding.buttonSaveGreenApi.setOnClickListener {
            val idInstance = binding.editGreenIdInstance.text.toString().trim()
            val apiToken = binding.editGreenApiToken.text.toString().trim()
            
            if (idInstance.isBlank() || apiToken.isBlank()) {
                Toast.makeText(this, "Please enter both GreenAPI credentials", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("green_id_instance", idInstance)
                .putString("green_api_token", apiToken)
                .apply()
            
            Toast.makeText(this, "GreenAPI credentials saved", Toast.LENGTH_SHORT).show()
        }

        // GreenAPI Clear
        binding.buttonClearGreenApi.setOnClickListener {
            prefs.edit()
                .remove("green_id_instance")
                .remove("green_api_token")
                .apply()
            binding.editGreenIdInstance.text?.clear()
            binding.editGreenApiToken.text?.clear()
            Toast.makeText(this, "GreenAPI credentials removed", Toast.LENGTH_SHORT).show()
        }

        // Clear All Messages
        binding.buttonClearAllMessages.setOnClickListener {
            showClearAllMessagesDialog()
        }
    }

    private fun showClearAllMessagesDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Messages")
            .setMessage("This will delete ALL captured messages from the database.\n\nYour API keys and settings will NOT be affected.\n\nAre you sure?")
            .setPositiveButton("Clear All") { _, _ ->
                scope.launch {
                    val database = com.example.whatsapp_summarizer.data.database.AppDatabase.getDatabase(applicationContext)
                    val deleted = database.messageDao().deleteAllMessages()
                    runOnUiThread {
                        Toast.makeText(this@SettingsActivity, "Deleted $deleted messages", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateApiKeyStatus() {
        val hasKey = secureStorage.hasApiKey()
        if (hasKey) {
            binding.chipApiStatus.text = "Configured"
            binding.chipApiStatus.setChipBackgroundColorResource(android.R.color.holo_green_dark)
        } else {
            binding.chipApiStatus.text = "Not Configured"
            binding.chipApiStatus.setChipBackgroundColorResource(android.R.color.holo_red_dark)
        }
    }

    /**
     * Personal chats captured by the old (buggy) group detection stay in the database
     * until deleted. This is destructive, so it is always confirmed and never runs
     * automatically on upgrade.
     */
    private fun confirmPurgePersonal() {
        val repository =
            (application as com.example.whatsapp_summarizer.WhatsAppSummarizerApp).repository
        scope.launch {
            val count = repository.getPersonalMessageCount()
            if (count == 0) {
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.no_personal_messages),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(getString(R.string.purge_personal_title))
                .setMessage("Permanently delete $count message(s) captured from personal conversations? Group chats are not affected.")
                .setPositiveButton("Delete") { _, _ ->
                    scope.launch {
                        val removed = repository.deletePersonalMessages()
                        Toast.makeText(
                            this@SettingsActivity,
                            "Deleted $removed personal message(s)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun isValidApiKey(key: String): Boolean {
        return key.startsWith("sk-") || key.length >= 20
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

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
