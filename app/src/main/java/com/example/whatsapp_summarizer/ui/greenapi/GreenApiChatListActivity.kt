package com.example.whatsapp_summarizer.ui.greenapi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.whatsapp_summarizer.data.remote.GreenApiRepository
import com.example.whatsapp_summarizer.data.remote.model.GreenApiContact
import com.example.whatsapp_summarizer.databinding.ActivityGreenApiChatListBinding
import kotlinx.coroutines.launch

class GreenApiChatListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGreenApiChatListBinding
    private val repository = GreenApiRepository()
    private lateinit var adapter: GreenApiContactAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        applyRtlSettings()
        
        binding = ActivityGreenApiChatListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "WhatsApp Groups"
            setDisplayHomeAsUpEnabled(true)
        }

        setupRecyclerView()
        loadContacts()
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
        adapter = GreenApiContactAdapter { contact ->
            showMessageCountDialog(contact)
        }

        binding.recyclerViewContacts.apply {
            layoutManager = LinearLayoutManager(this@GreenApiChatListActivity)
            adapter = this@GreenApiChatListActivity.adapter
        }
    }

    private fun loadContacts() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val idInstance = prefs.getString("green_id_instance", null)
        val apiToken = prefs.getString("green_api_token", null)

        if (idInstance.isNullOrBlank() || apiToken.isNullOrBlank()) {
            binding.progressBar.visibility = View.GONE
            binding.textStatus.text = "GreenAPI not configured. Please add credentials in Settings."
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.textStatus.text = "Loading contacts..."

        lifecycleScope.launch {
            val result = repository.getContacts(idInstance, apiToken, onlyGroups = true)
            
            binding.progressBar.visibility = View.GONE
            
            result.onSuccess { contacts ->
                if (contacts.isEmpty()) {
                    binding.textStatus.text = "No groups found."
                } else {
                    binding.textStatus.visibility = View.GONE
                    adapter.submitList(contacts)
                }
            }.onFailure { error ->
                binding.textStatus.text = "Error: ${error.message}"
                Toast.makeText(this@GreenApiChatListActivity, 
                    "Failed to load contacts: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showMessageCountDialog(contact: GreenApiContact) {
        val counts = arrayOf("10", "50", "100", "200")
        
        AlertDialog.Builder(this)
            .setTitle(contact.getDisplayName())
            .setItems(counts) { _, which ->
                val count = counts[which].toInt()
                openMessagePreview(contact, count)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openMessagePreview(contact: GreenApiContact, count: Int) {
        val intent = Intent(this, GreenApiMessagePreviewActivity::class.java).apply {
            putExtra("CHAT_ID", contact.id)
            putExtra("CHAT_NAME", contact.getDisplayName())
            putExtra("MESSAGE_COUNT", count)
        }
        startActivity(intent)
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
