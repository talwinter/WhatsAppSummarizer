package com.example.whatsapp_summarizer.ui.main

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.whatsapp_summarizer.R
import com.example.whatsapp_summarizer.databinding.ItemChatBinding
import com.example.whatsapp_summarizer.util.AvatarPalette

/** One row in the group list. Carrying the count here lets ListAdapter diff it. */
data class ChatListItem(
    val chatName: String,
    val messageCount: Int
)

class ChatListAdapter(
    private val onChatClick: (String) -> Unit,
    private val onSummarizeClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : ListAdapter<ChatListItem, ChatListAdapter.ChatViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChatViewHolder(
        private val binding: ItemChatBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChatListItem) {
            val context = binding.root.context

            binding.textChatName.text = item.chatName
            binding.textAvatar.text = AvatarPalette.initialsFor(item.chatName)
            binding.textMessageCount.text = context.resources.getQuantityString(
                R.plurals.message_count, item.messageCount, item.messageCount
            )

            // Shared palette, so a name is the same colour here, in the transcript
            // and on Ask source cards.
            binding.avatarBackground.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, AvatarPalette.colorFor(item.chatName))
            )

            binding.root.setOnClickListener { onChatClick(item.chatName) }
            binding.buttonSummarize.setOnClickListener { onSummarizeClick(item.chatName) }
            binding.buttonDelete.setOnClickListener { onDeleteClick(item.chatName) }
        }

    }

    class DiffCallback : DiffUtil.ItemCallback<ChatListItem>() {
        override fun areItemsTheSame(oldItem: ChatListItem, newItem: ChatListItem): Boolean {
            return oldItem.chatName == newItem.chatName
        }

        override fun areContentsTheSame(oldItem: ChatListItem, newItem: ChatListItem): Boolean {
            return oldItem == newItem
        }
    }
}
