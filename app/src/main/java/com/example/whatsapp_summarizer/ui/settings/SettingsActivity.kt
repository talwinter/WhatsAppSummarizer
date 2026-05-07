package com.example.whatsapp_summarizer.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.example.whatsapp_summarizer.R
import com.example.whatsapp_summarizer.databinding.ActivitySettingsBinding
import com.example.whatsapp_summarizer.util.LocalModelManager
import com.example.whatsapp_summarizer.util.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var secureStorage: SecureStorage
    private lateinit var localModelManager: LocalModelManager
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    // File picker for importing model
    private val pickModelFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val mimeType = contentResolver.getType(uri)
            val fileName = getFileName(uri)
            Log.d("Settings", "Selected file: $fileName (mime: $mimeType)")
            importModel(it)
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1) {
                result = result?.substring(cut!! + 1)
            }
        }
        return result ?: "unknown"
    }

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
        localModelManager = LocalModelManager(this)

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

        // RTL Toggle
        binding.switchRtl.isChecked = prefs.getBoolean("rtl_mode", false)

        // Hebrew Language Toggle
        binding.switchHebrew.isChecked = prefs.getBoolean("hebrew_language", false)

        // AI Provider
        val useLocalModel = prefs.getBoolean("use_local_model", false)
        binding.radioGroupAiProvider.check(
            if (useLocalModel) binding.radioLocal.id else binding.radioOpenai.id
        )

        // Local Model Section
        updateLocalModelUI()

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

        // AI Provider Selection
        binding.radioGroupAiProvider.setOnCheckedChangeListener { _, checkedId ->
            val useLocal = checkedId == binding.radioLocal.id
            prefs.edit().putBoolean("use_local_model", useLocal).apply()
            updateLocalModelUI()
        }

        // Import Model - opens file picker
        binding.buttonImportModel.setOnClickListener {
            showModelImportInfo()
        }

        // Delete Model
        binding.buttonDeleteModel.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete Local Model")
                .setMessage("Are you sure? You'll need to import it again to use offline summarization.")
                .setPositiveButton("Delete") { _, _ ->
                    localModelManager.deleteModel()
                    updateLocalModelUI()
                    Toast.makeText(this, "Model deleted", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
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

    private fun showModelImportInfo() {
        AlertDialog.Builder(this)
            .setTitle("Local Model - Not Available")
            .setMessage("Local model inference is currently not supported.\n\nReason:\n• TFLite models (like Google Gemma) require a native SentencePiece tokenizer which is not yet integrated\n• Importing large models (>1GB) often fails on Android due to memory mapping limitations\n• Without proper tokenization, the model output is meaningless\n\nRecommended:\nUse OpenAI or Gemini API for reliable summarization.\n\nIf you want to try anyway, you can import a .tflite file (max 2GB), but it likely won't produce useful results.")
            .setPositiveButton("Try Anyway") { _, _ ->
                pickModelFile.launch("*/*")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importModel(uri: Uri) {
        binding.buttonImportModel.isEnabled = false
        binding.buttonImportModel.text = "Importing..."
        binding.progressImport.isVisible = true

        val fileName = getFileName(uri)

        scope.launch {
            val result = localModelManager.importModelFromUri(uri, fileName)

            runOnUiThread {
                binding.progressImport.isVisible = false
                binding.buttonImportModel.isEnabled = true
                binding.buttonImportModel.text = getString(R.string.import_model)
                
                result.fold(
                    onSuccess = { size ->
                        Toast.makeText(this@SettingsActivity, "Model imported: $size", Toast.LENGTH_LONG).show()
                    },
                    onFailure = { error ->
                        Toast.makeText(this@SettingsActivity, "Import failed: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                )
                updateLocalModelUI()
            }
        }
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

    private fun updateLocalModelUI() {
        val isAvailable = localModelManager.isModelAvailable()
        val size = localModelManager.getModelSize()
        
        binding.textModelStatus.text = "Status: $size"
        
        if (isAvailable) {
            binding.buttonImportModel.isVisible = false
            binding.buttonDeleteModel.isVisible = true
            binding.progressImport.isVisible = false
        } else {
            binding.buttonImportModel.isVisible = true
            binding.buttonImportModel.text = getString(R.string.import_model)
            binding.buttonImportModel.isEnabled = true
            binding.buttonDeleteModel.isVisible = false
            binding.progressImport.isVisible = false
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
