package com.example.whatsapp_summarizer.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.whatsapp_summarizer.databinding.ItemChatBinding

class ChatListAdapter(
    private val onChatClick: (String) -> Unit,
    private val onSummarizeClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : ListAdapter<String, ChatListAdapter.ChatViewHolder>(DiffCallback()) {

    private var messageCounts: Map<String, Int> = emptyMap()

    fun setMessageCounts(counts: Map<String, Int>) {
        messageCounts = counts
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position), messageCounts[getItem(position)] ?: 0)
    }

    inner class ChatViewHolder(
        private val binding: ItemChatBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(chatName: String, count: Int) {
            binding.textChatName.text = chatName
            binding.textAvatar.text = chatName.take(1).uppercase()
            binding.textMessageCount.text = "$count messages"

            binding.root.setOnClickListener {
                onChatClick(chatName)
            }

            binding.buttonSummarize.setOnClickListener {
                onSummarizeClick(chatName)
            }

            binding.buttonDelete.setOnClickListener {
                onDeleteClick(chatName)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }
    }
}
