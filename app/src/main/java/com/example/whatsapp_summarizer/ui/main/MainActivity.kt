package com.example.whatsapp_summarizer.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.whatsapp_summarizer.databinding.ActivityMainBinding
import com.example.whatsapp_summarizer.ui.debug.DebugActivity
import com.example.whatsapp_summarizer.ui.greenapi.GreenApiChatListActivity
import com.example.whatsapp_summarizer.ui.group.ChatActivity
import com.example.whatsapp_summarizer.ui.settings.SettingsActivity
import com.example.whatsapp_summarizer.ui.summary.SummaryActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var chatAdapter: ChatListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Apply RTL setting before setting content view
        applyRtlSettings()
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        observeViewModel()
        checkNotificationAccess()

        binding.fabCleanup.setOnClickListener {
            showCleanupDialog()
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
        chatAdapter = ChatListAdapter(
            onChatClick = { chatName ->
                // Get all raw variations of this chat name
                val mapping = viewModel.nameMapping.value ?: emptyMap()
                val normalized = com.example.whatsapp_summarizer.util.ChatNameNormalizer.normalize(chatName)
                val variations = mapping[normalized] ?: listOf(chatName)
                
                val intent = Intent(this, ChatActivity::class.java).apply {
                    putExtra("CHAT_NAME", chatName)
                    putStringArrayListExtra("CHAT_VARIATIONS", ArrayList(variations))
                }
                startActivity(intent)
            },
            onSummarizeClick = { chatName ->
                val intent = Intent(this, SummaryActivity::class.java).apply {
                    putExtra("CHAT_NAME", chatName)
                }
                startActivity(intent)
            },
            onDeleteClick = { chatName ->
                showDeleteDialog(chatName)
            }
        )

        binding.recyclerViewChats.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = chatAdapter
        }
    }

    private fun observeViewModel() {
        viewModel.allChatNames.observe(this) { chatNames ->
            chatAdapter.submitList(chatNames)
            binding.emptyStateText.visibility = if (chatNames.isEmpty()) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }

        viewModel.chatCounts.observe(this) { counts ->
            val countMap = counts.associate { it.chatName to it.count }
            chatAdapter.setMessageCounts(countMap)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadChatCounts()
    }

    private fun checkNotificationAccess() {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        val packageName = packageName
        if (enabledListeners == null || !enabledListeners.contains(packageName)) {
            showNotificationAccessDialog()
        }
    }

    private fun showNotificationAccessDialog() {
        AlertDialog.Builder(this)
            .setTitle("Notification Access Required")
            .setMessage("This app needs access to your notifications to capture WhatsApp messages. Please enable it in settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()
    }

    private fun showDeleteDialog(chatName: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete Chat")
            .setMessage("Are you sure you want to delete all messages from '$chatName'?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteChat(chatName)
                Toast.makeText(this, "Chat deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCleanupDialog() {
        AlertDialog.Builder(this)
            .setTitle("Cleanup Old Messages")
            .setMessage("Delete messages older than 30 days?")
            .setPositiveButton("Cleanup") { _, _ ->
                viewModel.cleanupOldMessages()
                Toast.makeText(this, "Old messages cleaned up", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(com.example.whatsapp_summarizer.R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            com.example.whatsapp_summarizer.R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            com.example.whatsapp_summarizer.R.id.action_debug -> {
                startActivity(Intent(this, DebugActivity::class.java))
                true
            }
            com.example.whatsapp_summarizer.R.id.action_clear_duplicates -> {
                showClearDuplicatesDialog()
                true
            }
            com.example.whatsapp_summarizer.R.id.action_merge_chats -> {
                showMergeChatsDialog()
                true
            }
            com.example.whatsapp_summarizer.R.id.action_green_api -> {
                startActivity(Intent(this, GreenApiChatListActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showClearDuplicatesDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear Duplicate Messages")
            .setMessage("This will remove duplicate messages from the database. Continue?")
            .setPositiveButton("Clear") { _, _ ->
                viewModel.removeDuplicateMessages { removed ->
                    Toast.makeText(this, "Removed $removed duplicate messages", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMergeChatsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Merge Similar Chats")
            .setMessage("This will merge chats that have the same name but different invisible characters (like non-breaking spaces). Continue?")
            .setPositiveButton("Merge") { _, _ ->
                viewModel.mergeSimilarChats { merged ->
                    Toast.makeText(this, "Merged $merged similar chats", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
