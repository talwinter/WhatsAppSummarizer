package com.example.whatsapp_summarizer.feature.questions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.whatsapp_summarizer.R
import com.example.whatsapp_summarizer.WhatsAppSummarizerApp
import com.example.whatsapp_summarizer.databinding.ActivityOpenQuestionsBinding
import com.example.whatsapp_summarizer.databinding.ItemOpenQuestionBinding
import com.example.whatsapp_summarizer.ui.group.ChatActivity
import com.example.whatsapp_summarizer.util.SecureStorage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows questions from the user's groups that nobody answered.
 */
class OpenQuestionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOpenQuestionsBinding
    private lateinit var adapter: OpenQuestionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyRtlSettings()

        binding = ActivityOpenQuestionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.questions_title)
            setDisplayHomeAsUpEnabled(true)
        }

        adapter = OpenQuestionAdapter { question ->
            startActivity(
                Intent(this, ChatActivity::class.java).apply {
                    putExtra("CHAT_NAME", question.chatName)
                    putStringArrayListExtra("CHAT_VARIATIONS", arrayListOf(question.chatName))
                }
            )
        }
        binding.recyclerQuestions.apply {
            layoutManager = LinearLayoutManager(this@OpenQuestionsActivity)
            adapter = this@OpenQuestionsActivity.adapter
        }

        binding.buttonScan.setOnClickListener { scan() }
        scan()
    }

    private fun applyRtlSettings() {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        window.decorView.layoutDirection = if (prefs.getBoolean("rtl_mode", false)) {
            View.LAYOUT_DIRECTION_RTL
        } else {
            View.LAYOUT_DIRECTION_LTR
        }
    }

    private fun scan() {
        val apiKey = SecureStorage(this).getApiKey()
        if (apiKey.isNullOrBlank()) {
            showEmpty(R.string.questions_no_key_title, R.string.questions_no_key_body)
            return
        }

        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val inHebrew = prefs.getBoolean("hebrew_language", false)
        val repository = (application as WhatsAppSummarizerApp).repository

        setLoading(true)
        lifecycleScope.launch {
            try {
                val messages = repository.getGroupMessagesInRange(
                    OpenQuestionFinder.lookbackStart(),
                    System.currentTimeMillis()
                )
                val questions = OpenQuestionFinder.find(messages, apiKey, inHebrew)
                adapter.submitList(questions)
                setLoading(false)
                if (questions.isEmpty()) {
                    showEmpty(R.string.questions_empty_title, R.string.questions_empty_body)
                } else {
                    binding.emptyState.isVisible = false
                }
            } catch (e: Exception) {
                setLoading(false)
                Toast.makeText(
                    this@OpenQuestionsActivity,
                    getString(R.string.questions_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.layoutLoading.isVisible = loading
        binding.buttonScan.isEnabled = !loading
        if (loading) binding.emptyState.isVisible = false
    }

    private fun showEmpty(titleRes: Int, bodyRes: Int) {
        binding.emptyTitle.setText(titleRes)
        binding.emptyBody.setText(bodyRes)
        binding.emptyState.isVisible = true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            finish(); true
        } else {
            super.onOptionsItemSelected(item)
        }
    }
}

private class OpenQuestionAdapter(
    private val onClick: (OpenQuestionFinder.OpenQuestion) -> Unit
) : ListAdapter<OpenQuestionFinder.OpenQuestion, OpenQuestionAdapter.Holder>(Diff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            ItemOpenQuestionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemOpenQuestionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: OpenQuestionFinder.OpenQuestion) {
            binding.textQuestion.text = item.question
            binding.textGroup.text = item.chatName
            val when_ = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
                .format(Date(item.timestamp))
            binding.textMeta.text = "${item.senderName} · $when_"
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    class Diff : DiffUtil.ItemCallback<OpenQuestionFinder.OpenQuestion>() {
        override fun areItemsTheSame(
            oldItem: OpenQuestionFinder.OpenQuestion,
            newItem: OpenQuestionFinder.OpenQuestion
        ) = oldItem.chatName == newItem.chatName && oldItem.timestamp == newItem.timestamp

        override fun areContentsTheSame(
            oldItem: OpenQuestionFinder.OpenQuestion,
            newItem: OpenQuestionFinder.OpenQuestion
        ) = oldItem == newItem
    }
}
