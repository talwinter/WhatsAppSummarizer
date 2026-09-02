package com.example.whatsapp_summarizer.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.whatsapp_summarizer.R
import com.example.whatsapp_summarizer.databinding.ActivityMainBinding
import com.example.whatsapp_summarizer.feature.ask.AskActivity
import com.example.whatsapp_summarizer.feature.digest.DigestActivity
import com.example.whatsapp_summarizer.feature.questions.OpenQuestionsActivity
import com.example.whatsapp_summarizer.ui.debug.DebugActivity
import com.example.whatsapp_summarizer.ui.greenapi.GreenApiChatListActivity
import com.example.whatsapp_summarizer.ui.group.ChatActivity
import com.example.whatsapp_summarizer.ui.settings.SettingsActivity
import com.example.whatsapp_summarizer.ui.summary.SummaryActivity
import com.example.whatsapp_summarizer.util.ChatNameNormalizer

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var chatAdapter: ChatListAdapter

    /** Latest unfiltered list; the search box narrows this without refetching. */
    private var allItems: List<ChatListItem> = emptyList()
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply RTL setting before setting content view
        applyRtlSettings()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        setupRecyclerView()
        setupSearch()
        observeViewModel()
        checkNotificationAccess()

        binding.fabCleanup.setOnClickListener { showCleanupDialog() }
        binding.layoutStatus.setOnClickListener {
            if (!hasNotificationAccess()) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
    }

    private fun applyRtlSettings() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val rtlEnabled = prefs.getBoolean("rtl_mode", false)
        if (rtlEnabled) {
            window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        } else {
            window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatListAdapter(
            onChatClick = { chatName ->
                // Get all raw variations of this chat name
                val mapping = viewModel.nameMapping.value ?: emptyMap()
                val normalized = ChatNameNormalizer.normalize(chatName)
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
            onDeleteClick = { chatName -> showDeleteDialog(chatName) }
        )

        binding.recyclerViewChats.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = chatAdapter
        }
    }

    private fun setupSearch() {
        binding.editSearch.doAfterTextChanged { text ->
            searchQuery = text?.toString()?.trim().orEmpty()
            renderList()
        }
    }

    private fun observeViewModel() {
        // Chat names and counts arrive from separate queries; rebuild rows when
        // either changes so a row never shows a stale count.
        viewModel.allChatNames.observe(this) { rebuildItems() }
        viewModel.chatCounts.observe(this) { rebuildItems() }
    }

    private fun rebuildItems() {
        val names = viewModel.allChatNames.value ?: emptyList()
        val counts = viewModel.chatCounts.value
            ?.associate { it.chatName to it.count }
            ?: emptyMap()

        allItems = names
            .map { ChatListItem(it, counts[it] ?: 0) }
            .sortedByDescending { it.messageCount }

        updateHeader()
        renderList()
    }

    private fun renderList() {
        val visible = if (searchQuery.isBlank()) {
            allItems
        } else {
            allItems.filter { it.chatName.contains(searchQuery, ignoreCase = true) }
        }

        chatAdapter.submitList(visible)

        val isEmpty = visible.isEmpty()
        binding.emptyState.isVisible = isEmpty
        if (isEmpty) {
            val searching = searchQuery.isNotBlank()
            binding.emptyStateTitle.setText(
                if (searching) R.string.no_search_results_title else R.string.empty_state_title
            )
            binding.emptyStateText.setText(
                if (searching) R.string.no_search_results else R.string.empty_state_message
            )
        }
    }

    private fun updateHeader() {
        val totalMessages = allItems.sumOf { it.messageCount }
        binding.textSubhead.text = if (allItems.isEmpty()) {
            getString(R.string.subhead_empty)
        } else {
            getString(R.string.subhead_stats, allItems.size, totalMessages)
        }
        updateStatusPill()
    }

    private fun updateStatusPill() {
        val granted = hasNotificationAccess()
        binding.textStatus.setText(
            if (granted) R.string.status_capturing else R.string.status_no_access
        )
        val dotColor = if (granted) R.color.primary else R.color.warning
        binding.dotStatus.backgroundTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, dotColor))
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadChatCounts()
        updateStatusPill()
    }

    private fun hasNotificationAccess(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        return enabledListeners != null && enabledListeners.contains(packageName)
    }

    private fun checkNotificationAccess() {
        if (!hasNotificationAccess()) {
            showNotificationAccessDialog()
        }
    }

    private fun showNotificationAccessDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.notification_access_title)
            .setMessage(R.string.notification_access_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .setCancelable(false)
            .show()
    }

    private fun showDeleteDialog(chatName: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_chat_title)
            .setMessage(getString(R.string.delete_chat_message, chatName))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.deleteChat(chatName)
                toast(getString(R.string.chat_deleted))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showCleanupDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.cleanup_title)
            .setMessage(R.string.cleanup_message)
            .setPositiveButton(R.string.action_cleanup) { _, _ ->
                viewModel.cleanupOldMessages()
                toast(getString(R.string.cleanup_done))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_digest -> {
                startActivity(Intent(this, DigestActivity::class.java))
                true
            }
            R.id.action_ask -> {
                startActivity(Intent(this, AskActivity::class.java))
                true
            }
            R.id.action_open_questions -> {
                startActivity(Intent(this, OpenQuestionsActivity::class.java))
                true
            }
            R.id.action_debug -> {
                startActivity(Intent(this, DebugActivity::class.java))
                true
            }
            R.id.action_clear_duplicates -> {
                showClearDuplicatesDialog()
                true
            }
            R.id.action_merge_chats -> {
                showMergeChatsDialog()
                true
            }
            R.id.action_green_api -> {
                startActivity(Intent(this, GreenApiChatListActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showClearDuplicatesDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_duplicates_title)
            .setMessage(R.string.clear_duplicates_message)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                viewModel.removeDuplicateMessages { removed ->
                    toast("Removed $removed duplicate messages")
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showMergeChatsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.merge_chats_title)
            .setMessage(R.string.merge_chats_message)
            .setPositiveButton(R.string.action_merge) { _, _ ->
                viewModel.mergeSimilarChats { merged ->
                    toast("Merged $merged similar chats")
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
