package com.example.whatsapp_summarizer.ui.group

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.whatsapp_summarizer.data.model.Message
import com.example.whatsapp_summarizer.databinding.ItemMessageBinding

class MessageAdapter : ListAdapter<Message, MessageAdapter.MessageViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class MessageViewHolder(
        private val binding: ItemMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: Message, position: Int) {
            binding.textSenderName.text = message.senderName
            binding.textMessageContent.text = message.messageContent
            binding.textTimestamp.text = message.getFormattedTime()
            
            // Show date separator if it's a new day compared to previous message
            val previousMessage = if (position > 0) {
                getItem(position - 1)
            } else null
            
            if (previousMessage == null || previousMessage.getFormattedDate() != message.getFormattedDate()) {
                binding.textDateSeparator.visibility = View.VISIBLE
                binding.textDateSeparator.text = message.getFormattedDate()
            } else {
                binding.textDateSeparator.visibility = View.GONE
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
            return oldItem == newItem
        }
    }
}
