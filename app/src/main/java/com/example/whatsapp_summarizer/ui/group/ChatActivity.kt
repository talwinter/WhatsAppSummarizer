package com.example.whatsapp_summarizer.ui.group

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.whatsapp_summarizer.R
import com.example.whatsapp_summarizer.databinding.ActivityChatBinding

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val viewModel: ChatViewModel by viewModels()
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var layoutManager: LinearLayoutManager

    private var chatName: String = ""
    private var chatVariations: List<String> = emptyList()
    private var isFirstLoad = true

    /** Set when opened from an Ask citation: the message to land on. */
    private var jumpToMessageId: Long = 0L
    private var hasJumped = false

    companion object {
        const val EXTRA_CHAT_NAME = "CHAT_NAME"
        const val EXTRA_CHAT_VARIATIONS = "CHAT_VARIATIONS"

        /** Id of a specific captured message to scroll to and highlight. */
        const val EXTRA_MESSAGE_ID = "MESSAGE_ID"

        /** How long the highlight stays before fading back to normal. */
        private const val HIGHLIGHT_MILLIS = 3500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyRtlSettings()

        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        chatName = intent.getStringExtra(EXTRA_CHAT_NAME) ?: run {
            Toast.makeText(this, getString(R.string.chat_missing), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        chatVariations = intent.getStringArrayListExtra(EXTRA_CHAT_VARIATIONS) ?: listOf(chatName)
        jumpToMessageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0L)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = chatName
            setDisplayHomeAsUpEnabled(true)
        }

        setupRecyclerView()
        observeViewModel()

        binding.fabJumpLatest.setOnClickListener {
            val last = messageAdapter.itemCount - 1
            if (last >= 0) binding.recyclerViewMessages.smoothScrollToPosition(last)
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

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter()
        layoutManager = LinearLayoutManager(this)
        binding.recyclerViewMessages.apply {
            layoutManager = this@ChatActivity.layoutManager
            adapter = messageAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                    // Offer a way back once the newest message is off screen.
                    val lastVisible = this@ChatActivity.layoutManager
                        .findLastVisibleItemPosition()
                    val atEnd = lastVisible >= messageAdapter.itemCount - 2
                    binding.fabJumpLatest.isVisible = !atEnd && messageAdapter.itemCount > 0
                }
            })
        }
    }

    private fun observeViewModel() {
        viewModel.getMessagesByVariations(chatVariations).observe(this) { messages ->
            messageAdapter.submitList(messages) {
                // Runs after the list has been diffed and laid out, which is the only
                // point where positions are valid to scroll to.
                onListReady(messages.size)
            }
            supportActionBar?.subtitle = resources.getQuantityString(
                R.plurals.message_count, messages.size, messages.size
            )
        }
    }

    private fun onListReady(count: Int) {
        if (count == 0) return

        if (jumpToMessageId != 0L && !hasJumped) {
            val position = messageAdapter.positionOf(jumpToMessageId)
            if (position >= 0) {
                hasJumped = true
                isFirstLoad = false
                // Offset so the target sits a little below the top edge rather than
                // flush against the toolbar.
                layoutManager.scrollToPositionWithOffset(position, 120)
                messageAdapter.setHighlightedMessage(jumpToMessageId)
                binding.recyclerViewMessages.postDelayed({
                    messageAdapter.setHighlightedMessage(0L)
                }, HIGHLIGHT_MILLIS)
                return
            }
            // The cited message is not in this chat's list; fall through to the
            // normal "newest first view" behaviour rather than doing nothing.
            hasJumped = true
        }

        if (isFirstLoad) {
            binding.recyclerViewMessages.scrollToPosition(count - 1)
            isFirstLoad = false
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
