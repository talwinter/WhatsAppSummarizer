package com.example.whatsapp_summarizer.ui.greenapi

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.whatsapp_summarizer.data.remote.model.GreenApiContact
import com.example.whatsapp_summarizer.databinding.ItemChatBinding

class GreenApiContactAdapter(
    private val onContactClick: (GreenApiContact) -> Unit
) : ListAdapter<GreenApiContact, GreenApiContactAdapter.ContactViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val binding = ItemChatBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ContactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ContactViewHolder(
        private val binding: ItemChatBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: GreenApiContact) {
            binding.textChatName.text = contact.getDisplayName()
            binding.textAvatar.text = contact.getDisplayName().take(1).uppercase()
            binding.textMessageCount.text = if (contact.isGroup()) "Group" else "Individual"

            binding.root.setOnClickListener {
                onContactClick(contact)
            }

            // Hide summarize and delete buttons for GreenAPI contacts
            binding.buttonSummarize.visibility = android.view.View.GONE
            binding.buttonDelete.visibility = android.view.View.GONE
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<GreenApiContact>() {
        override fun areItemsTheSame(oldItem: GreenApiContact, newItem: GreenApiContact): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: GreenApiContact, newItem: GreenApiContact): Boolean {
            return oldItem == newItem
        }
    }
}
