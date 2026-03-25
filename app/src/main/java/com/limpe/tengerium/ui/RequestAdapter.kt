package com.limpe.tengerium.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.limpe.tengerium.databinding.ItemRequestBinding
import com.limpe.tengerium.domain.model.Contact

class RequestAdapter(
    private val onAccept: (Contact) -> Unit,
    private val onDecline: (Contact) -> Unit
) : ListAdapter<Contact, RequestAdapter.ViewHolder>(DiffCallback) {

    class ViewHolder(private val binding: ItemRequestBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(contact: Contact, onAccept: (Contact) -> Unit, onDecline: (Contact) -> Unit) {
            binding.tvAccount.text = contact.account
            AvatarUtils.loadAvatar(binding.ivAvatar, contact.avatarUrl, contact.account)
            
            binding.btnAccept.setOnClickListener { onAccept(contact) }
            binding.btnDecline.setOnClickListener { onDecline(contact) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onAccept, onDecline)
    }

    object DiffCallback : DiffUtil.ItemCallback<Contact>() {
        override fun areItemsTheSame(oldItem: Contact, newItem: Contact) = oldItem.account == newItem.account
        override fun areContentsTheSame(oldItem: Contact, newItem: Contact) = oldItem == newItem
    }
}
