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
import kotlin.math.abs

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

    companion object {
        private val AVATAR_COLORS = intArrayOf(
            R.color.avatar_1,
            R.color.avatar_2,
            R.color.avatar_3,
            R.color.avatar_4,
            R.color.avatar_5,
            R.color.avatar_6
        )
    }

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
            binding.textAvatar.text = initials(item.chatName)
            binding.textMessageCount.text = context.resources.getQuantityString(
                R.plurals.message_count, item.messageCount, item.messageCount
            )

            // Hash the name so a group keeps the same avatar colour across launches.
            val colorRes = AVATAR_COLORS[abs(item.chatName.hashCode()) % AVATAR_COLORS.size]
            binding.avatarBackground.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, colorRes))

            binding.root.setOnClickListener { onChatClick(item.chatName) }
            binding.buttonSummarize.setOnClickListener { onSummarizeClick(item.chatName) }
            binding.buttonDelete.setOnClickListener { onDeleteClick(item.chatName) }
        }

        /** First letter of the first two words, so "Dev Team" reads as "DT". */
        private fun initials(name: String): String {
            val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            return when {
                words.isEmpty() -> "?"
                words.size == 1 -> words[0].take(1).uppercase()
                else -> (words[0].take(1) + words[1].take(1)).uppercase()
            }
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
