package com.example.whatsapp_summarizer.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.whatsapp_summarizer.databinding.ActivityDebugBinding
import com.example.whatsapp_summarizer.util.NotificationDebugLog
import com.google.android.material.textview.MaterialTextView

class DebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding
    private lateinit var adapter: DebugLogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Apply RTL setting before setting content view
        applyRtlSettings()
        
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Debug Log"
            setDisplayHomeAsUpEnabled(true)
        }

        setupRecyclerView()
        refreshLogs()

        binding.buttonRefresh.setOnClickListener {
            refreshLogs()
            Toast.makeText(this, "Logs refreshed", Toast.LENGTH_SHORT).show()
        }

        binding.buttonClear.setOnClickListener {
            NotificationDebugLog.clear()
            refreshLogs()
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }

        binding.buttonCopy.setOnClickListener {
            copyLogsToClipboard()
        }

        binding.buttonShare.setOnClickListener {
            shareLogs()
        }
    }

    private fun copyLogsToClipboard() {
        val logs = NotificationDebugLog.getLogs()
        if (logs.isEmpty()) {
            Toast.makeText(this, "No logs to copy", Toast.LENGTH_SHORT).show()
            return
        }

        val text = formatLogsForSharing(logs)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Debug Logs", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        val logs = NotificationDebugLog.getLogs()
        if (logs.isEmpty()) {
            Toast.makeText(this, "No logs to share", Toast.LENGTH_SHORT).show()
            return
        }

        val text = formatLogsForSharing(logs)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "WhatsApp Summarizer Debug Logs")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share Logs"))
    }

    private fun formatLogsForSharing(logs: List<NotificationDebugLog.LogEntry>): String {
        val sb = StringBuilder()
        sb.append("=== WhatsApp Summarizer Debug Logs ===\n\n")
        
        logs.reversed().forEach { entry ->
            val time = NotificationDebugLog.getFormattedTime(entry.timestamp)
            sb.append("[$time] ${entry.action}: ${entry.reason}\n")
            sb.append("  Title: '${entry.rawTitle}'\n")
            sb.append("  Text: '${entry.rawText}'\n")
            if (entry.parsedChatName.isNotBlank()) {
                sb.append("  Chat: '${entry.parsedChatName}'\n")
            }
            if (entry.parsedSender.isNotBlank()) {
                sb.append("  Sender: '${entry.parsedSender}'\n")
            }
            if (entry.parsedMessage.isNotBlank()) {
                sb.append("  Message: '${entry.parsedMessage.take(100)}'\n")
            }
            sb.append("\n")
        }
        
        return sb.toString()
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
        adapter = DebugLogAdapter()
        binding.recyclerViewLogs.apply {
            layoutManager = LinearLayoutManager(this@DebugActivity)
            adapter = this@DebugActivity.adapter
        }
    }

    private fun refreshLogs() {
        val logs = NotificationDebugLog.getLogs().reversed() // Show newest first
        adapter.submitList(logs)
        binding.textCount.text = "${logs.size} entries"
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

class DebugLogAdapter : RecyclerView.Adapter<DebugLogAdapter.ViewHolder>() {

    private var logs: List<NotificationDebugLog.LogEntry> = emptyList()

    fun submitList(newLogs: List<NotificationDebugLog.LogEntry>) {
        logs = newLogs
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(logs[position])
    }

    override fun getItemCount() = logs.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text1: MaterialTextView = itemView.findViewById(android.R.id.text1)
        private val text2: MaterialTextView = itemView.findViewById(android.R.id.text2)

        fun bind(entry: NotificationDebugLog.LogEntry) {
            val time = NotificationDebugLog.getFormattedTime(entry.timestamp)
            val rawTitleVisible = NotificationDebugLog.showInvisibleChars(entry.rawTitle)
            val rawTextVisible = NotificationDebugLog.showInvisibleChars(entry.rawText)
            
            val titleColor = when (entry.action) {
                "SAVED" -> "✅"
                "SKIPPED" -> "❌"
                "DUPLICATE" -> "⚠️"
                else -> "📋"
            }

            text1.text = "$titleColor [$time] ${entry.action}: ${entry.reason}"
            
            val details = StringBuilder()
            details.append("Title: '$rawTitleVisible'\n")
            details.append("Text: '$rawTextVisible'")
            
            if (entry.parsedChatName.isNotBlank()) {
                details.append("\nChat: '${entry.parsedChatName}'")
            }
            if (entry.parsedSender.isNotBlank()) {
                details.append("\nSender: '${entry.parsedSender}'")
            }
            if (entry.parsedMessage.isNotBlank()) {
                details.append("\nMsg: '${entry.parsedMessage.take(50)}'")
            }
            
            text2.text = details.toString()
            text2.setPadding(16, 8, 16, 16)
            text2.textSize = 12f
        }
    }
}
