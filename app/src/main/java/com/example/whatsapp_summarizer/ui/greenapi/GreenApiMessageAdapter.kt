package com.example.whatsapp_summarizer.ui.greenapi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.whatsapp_summarizer.data.remote.model.GreenApiMessage
import com.example.whatsapp_summarizer.databinding.ItemMessageBinding
import java.text.SimpleDateFormat
import java.util.*

class GreenApiMessageAdapter : ListAdapter<GreenApiMessage, GreenApiMessageAdapter.MessageViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(
        private val binding: ItemMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: GreenApiMessage) {
            binding.textSenderName.text = message.getSenderDisplayName()
            binding.textMessageContent.text = message.getDisplayText()
            
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            binding.textTimestamp.text = sdf.format(Date(message.timestamp * 1000))
            
            // Hide date separator for GreenAPI messages (simpler UI)
            binding.textDateSeparator.visibility = View.GONE
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<GreenApiMessage>() {
        override fun areItemsTheSame(oldItem: GreenApiMessage, newItem: GreenApiMessage): Boolean {
            return oldItem.idMessage == newItem.idMessage
        }

        override fun areContentsTheSame(oldItem: GreenApiMessage, newItem: GreenApiMessage): Boolean {
            return oldItem == newItem
        }
    }
}
