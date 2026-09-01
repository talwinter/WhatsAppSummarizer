package com.example.whatsapp_summarizer.ui.greenapi

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.whatsapp_summarizer.data.remote.GreenApiRepository
import com.example.whatsapp_summarizer.data.remote.model.GreenApiMessage
import com.example.whatsapp_summarizer.databinding.ActivityGreenApiPreviewBinding
import com.example.whatsapp_summarizer.ui.summary.AiSummarizer
import com.example.whatsapp_summarizer.util.SecureStorage
import kotlinx.coroutines.launch

class GreenApiMessagePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGreenApiPreviewBinding
    private val repository = GreenApiRepository()
    private lateinit var adapter: GreenApiMessageAdapter
    private lateinit var secureStorage: SecureStorage
    
    private var chatId: String = ""
    private var chatName: String = ""
    private var messageCount: Int = 50

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        applyRtlSettings()
        
        binding = ActivityGreenApiPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chatId = intent.getStringExtra("CHAT_ID") ?: return finish()
        chatName = intent.getStringExtra("CHAT_NAME") ?: "Unknown"
        messageCount = intent.getIntExtra("MESSAGE_COUNT", 50)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = chatName
            setDisplayHomeAsUpEnabled(true)
        }

        secureStorage = SecureStorage(this)

        setupRecyclerView()
        loadMessages()

        binding.buttonSummarize.setOnClickListener {
            summarizeMessages()
        }
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

    private fun setupRecyclerView() {
        adapter = GreenApiMessageAdapter()
        binding.recyclerViewMessages.apply {
            layoutManager = LinearLayoutManager(this@GreenApiMessagePreviewActivity)
            adapter = this@GreenApiMessagePreviewActivity.adapter
        }
    }

    private fun loadMessages() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val idInstance = prefs.getString("green_id_instance", null)
        val apiToken = prefs.getString("green_api_token", null)

        if (idInstance.isNullOrBlank() || apiToken.isNullOrBlank()) {
            Toast.makeText(this, "GreenAPI not configured", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.buttonSummarize.isEnabled = false

        lifecycleScope.launch {
            val result = repository.getChatHistory(idInstance, apiToken, chatId, messageCount)
            
            binding.progressBar.visibility = View.GONE
            binding.buttonSummarize.isEnabled = true
            
            result.onSuccess { messages ->
                if (messages.isEmpty()) {
                    binding.textStatus.text = "No messages found."
                    binding.textStatus.visibility = View.VISIBLE
                } else {
                    binding.textStatus.visibility = View.GONE
                    adapter.submitList(messages)
                    binding.recyclerViewMessages.scrollToPosition(messages.size - 1)
                }
            }.onFailure { error ->
                binding.textStatus.text = "Error: ${error.message}"
                binding.textStatus.visibility = View.VISIBLE
                Toast.makeText(this@GreenApiMessagePreviewActivity, 
                    "Failed to load messages: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun summarizeMessages() {
        val messages = adapter.currentList
        if (messages.isEmpty()) {
            Toast.makeText(this, "No messages to summarize", Toast.LENGTH_SHORT).show()
            return
        }

        val apiKey = secureStorage.getApiKey()
        if (apiKey == null) {
            Toast.makeText(this, "OpenAI API key not configured", Toast.LENGTH_LONG).show()
            return
        }

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val inHebrew = prefs.getBoolean("hebrew_language", false)

        binding.progressBar.visibility = View.VISIBLE
        binding.buttonSummarize.isEnabled = false

        lifecycleScope.launch {
            try {
                val conversation = messages.joinToString("\n") { msg ->
                    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(msg.timestamp * 1000))
                    "[$time] ${msg.getSenderDisplayName()}: ${msg.getDisplayText()}"
                }

                val summary = AiSummarizer.summarizeMessages(
                    messages = messages.map { 
                        com.example.whatsapp_summarizer.data.model.Message(
                            chatName = chatName,
                            senderName = it.getSenderDisplayName(),
                            messageContent = it.getDisplayText(),
                            timestamp = it.timestamp * 1000
                        )
                    },
                    apiKey = apiKey,
                    inHebrew = inHebrew
                )

                showSummaryDialog(summary)
            } catch (e: Exception) {
                Toast.makeText(this@GreenApiMessagePreviewActivity, 
                    "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.buttonSummarize.isEnabled = true
            }
        }
    }

    private fun showSummaryDialog(summary: String) {
        AlertDialog.Builder(this)
            .setTitle("Summary: $chatName")
            .setMessage(summary)
            .setPositiveButton("OK", null)
            .show()
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
