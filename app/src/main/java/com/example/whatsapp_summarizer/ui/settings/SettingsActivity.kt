package com.example.whatsapp_summarizer.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.example.whatsapp_summarizer.R
import com.example.whatsapp_summarizer.databinding.ActivitySettingsBinding
import com.example.whatsapp_summarizer.feature.alerts.AlertSettings
import com.example.whatsapp_summarizer.feature.digest.DigestSettings
import com.example.whatsapp_summarizer.feature.digest.DigestWorker
import com.example.whatsapp_summarizer.util.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var secureStorage: SecureStorage
    private lateinit var alertSettings: AlertSettings
    private lateinit var digestSettings: DigestSettings

    /**
     * Android 13+ requires POST_NOTIFICATIONS before we can reach the user. Asked
     * at the moment they switch alerts on, which is when the reason is obvious.
     */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this, getString(R.string.alerts_need_permission), Toast.LENGTH_LONG
                ).show()
            }
        }
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
        alertSettings = AlertSettings(this)
        digestSettings = DigestSettings(this)

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

        // Daily digest
        binding.switchDigest.isChecked = digestSettings.enabled
        updateDigestTimeLabel()

        // Smart alerts
        binding.switchAlerts.isChecked = alertSettings.enabled
        if (binding.editAlertRules.text.isNullOrEmpty()) {
            binding.editAlertRules.setText(alertSettings.rules)
        }

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

        // Daily digest on/off
        binding.switchDigest.setOnCheckedChangeListener { _, isChecked ->
            digestSettings.enabled = isChecked
            DigestWorker.rescheduleNow(this)
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotificationPermission.launch(
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )
                }
                if (!secureStorage.hasApiKey()) {
                    Toast.makeText(this, getString(R.string.alerts_need_key), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.digest_scheduled, digestSettings.formattedTime()),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                Toast.makeText(this, getString(R.string.digest_off), Toast.LENGTH_SHORT).show()
            }
        }

        // Digest delivery time
        binding.buttonDigestTime.setOnClickListener {
            android.app.TimePickerDialog(
                this,
                { _, hour, minute ->
                    digestSettings.hour = hour
                    digestSettings.minute = minute
                    updateDigestTimeLabel()
                    // UPDATE, so a new time takes effect today rather than tomorrow.
                    DigestWorker.rescheduleNow(this)
                },
                digestSettings.hour,
                digestSettings.minute,
                true
            ).show()
        }

        // Smart alerts on/off
        binding.switchAlerts.setOnCheckedChangeListener { _, isChecked ->
            alertSettings.enabled = isChecked
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotificationPermission.launch(
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )
                }
                // Say plainly what is still missing rather than failing silently later.
                when {
                    !secureStorage.hasApiKey() ->
                        Toast.makeText(this, getString(R.string.alerts_need_key), Toast.LENGTH_LONG).show()
                    alertSettings.rules.isBlank() ->
                        Toast.makeText(this, getString(R.string.alerts_need_rules), Toast.LENGTH_LONG).show()
                }
            }
        }

        // Alert rules - saved as typed
        binding.editAlertRules.doAfterTextChanged { text ->
            alertSettings.rules = text?.toString()?.trim().orEmpty()
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
    private fun updateDigestTimeLabel() {
        binding.buttonDigestTime.text =
            getString(R.string.digest_time_label, digestSettings.formattedTime())
    }

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
