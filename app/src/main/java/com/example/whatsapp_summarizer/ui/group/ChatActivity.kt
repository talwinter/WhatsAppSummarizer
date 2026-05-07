package com.example.whatsapp_summarizer.ui.group

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.whatsapp_summarizer.databinding.ActivityChatBinding

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val viewModel: ChatViewModel by viewModels()
    private lateinit var messageAdapter: MessageAdapter
    private var chatName: String = ""
    private var chatVariations: List<String> = emptyList()
    private var isFirstLoad = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Apply RTL setting before setting content view
        applyRtlSettings()
        
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chatName = intent.getStringExtra("CHAT_NAME") ?: run {
            Toast.makeText(this, "Error: No chat selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        chatVariations = intent.getStringArrayListExtra("CHAT_VARIATIONS") ?: listOf(chatName)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = chatName
            setDisplayHomeAsUpEnabled(true)
        }

        setupRecyclerView()
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

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter()
        binding.recyclerViewMessages.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity)
            adapter = messageAdapter
        }
    }

    private fun observeViewModel() {
        viewModel.getMessagesByVariations(chatVariations).observe(this) { messages ->
            messageAdapter.submitList(messages)
            // Only scroll to bottom on first load, not on every update
            if (isFirstLoad && messages.isNotEmpty()) {
                binding.recyclerViewMessages.scrollToPosition(messages.size - 1)
                isFirstLoad = false
            }
        }
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
