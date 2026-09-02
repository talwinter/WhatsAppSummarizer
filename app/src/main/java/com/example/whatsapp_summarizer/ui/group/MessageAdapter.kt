package com.example.whatsapp_summarizer.ui.group

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.whatsapp_summarizer.data.model.Message
import com.example.whatsapp_summarizer.databinding.ItemMessageBinding
import com.example.whatsapp_summarizer.util.AvatarPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders captured messages as a transcript.
 *
 * Consecutive messages from one sender share a header and hide the avatar, so a busy
 * group reads as blocks per speaker instead of a wall of repeated names.
 */
class MessageAdapter : ListAdapter<Message, MessageAdapter.MessageViewHolder>(DiffCallback()) {

    /** Row to mark, set when arriving from an Ask citation. 0 means none. */
    private var highlightedId: Long = 0L

    fun setHighlightedMessage(id: Long) {
        val previous = highlightedId
        highlightedId = id
        // Refresh just the two affected rows rather than the whole list.
        listOf(previous, id)
            .filter { it != 0L }
            .forEach { target ->
                val index = currentList.indexOfFirst { it.id == target }
                if (index >= 0) notifyItemChanged(index)
            }
    }

    fun positionOf(messageId: Long): Int = currentList.indexOfFirst { it.id == messageId }

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
            val context = binding.root.context
            val previous = if (position > 0) getItem(position - 1) else null

            val isNewDay = previous == null ||
                previous.getFormattedDate() != message.getFormattedDate()

            // A header starts a new day or a new speaker.
            val startsBlock = isNewDay || previous?.senderName != message.senderName

            binding.layoutDateSeparator.isVisible = isNewDay
            if (isNewDay) {
                binding.textDateSeparator.text = friendlyDate(message.timestamp)
            }

            binding.layoutSenderHeader.isVisible = startsBlock
            // The avatar gutter keeps its width either way, so text stays aligned.
            binding.avatarBackground.isVisible = startsBlock
            binding.textAvatar.isVisible = startsBlock

            if (startsBlock) {
                binding.textSenderName.text = message.senderName
                binding.textTimestamp.text = message.getFormattedTime()
                binding.textAvatar.text = AvatarPalette.initialsFor(message.senderName)
                binding.avatarBackground.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, AvatarPalette.colorFor(message.senderName))
                )
            }

            binding.textMessageContent.text = message.messageContent

            // Extra breathing room above a new speaker, tight within a block.
            binding.messageRow.setPadding(
                binding.messageRow.paddingLeft,
                if (startsBlock && !isNewDay) dp(context, 10) else dp(context, 3),
                binding.messageRow.paddingRight,
                dp(context, 3)
            )

            if (message.id == highlightedId) {
                binding.messageRow.setBackgroundResource(
                    com.example.whatsapp_summarizer.R.drawable.message_highlight
                )
            } else {
                binding.messageRow.background = null
            }
        }

        private fun dp(context: android.content.Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()

        /** "Today" / "Yesterday" / "2 September" - a date the reader can place. */
        private fun friendlyDate(timestamp: Long): String {
            val dayMillis = 24L * 60L * 60L * 1000L
            val startOfToday = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            val context = binding.root.context
            return when {
                timestamp >= startOfToday ->
                    context.getString(com.example.whatsapp_summarizer.R.string.date_today)
                timestamp >= startOfToday - dayMillis ->
                    context.getString(com.example.whatsapp_summarizer.R.string.date_yesterday)
                else -> SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(timestamp))
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
