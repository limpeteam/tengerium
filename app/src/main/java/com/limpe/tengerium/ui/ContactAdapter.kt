package com.limpe.tengerium.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.limpe.tengerium.R
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.databinding.ItemContactGroupBinding
import com.limpe.tengerium.databinding.ItemDateHeaderBinding
import com.limpe.tengerium.databinding.ItemRequestsHeaderBinding
import com.limpe.tengerium.databinding.ItemUserRowBinding
import com.limpe.tengerium.domain.model.Contact
import com.limpe.tengerium.domain.model.Group
import com.limpe.tengerium.util.AnimationHelper
import com.limpe.tengerium.util.FormattingUtils
import java.util.*

class ContactAdapter(
    private val securePrefs: SecurePrefs,
    private val onClick: (Contact) -> Unit,
    private val onLongClick: (View, Contact) -> Unit,
    private val onRequestsClick: () -> Unit,
    private val onGroupClick: (Group) -> Unit,
    private val onGroupLongClick: (View, Group) -> Unit
) : ListAdapter<ContactAdapter.ContactItem, RecyclerView.ViewHolder>(DiffCallback()) {

    private var highlightedAccount: String? = null

    sealed class ContactItem {
        data class User(val contact: Contact, val isMuted: Boolean = false) : ContactItem()
        data class GroupHeader(val group: Group, val onlineCount: Int, val totalCount: Int, val isExpanded: Boolean) : ContactItem()
        data class Header(val count: Int) : ContactItem()
        object NotConnected : ContactItem()
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ContactItem.User -> 0
            is ContactItem.GroupHeader -> 1
            is ContactItem.Header -> 2
            is ContactItem.NotConnected -> 3
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> ContactViewHolder(ItemUserRowBinding.inflate(inflater, parent, false))
            1 -> GroupViewHolder(ItemContactGroupBinding.inflate(inflater, parent, false))
            2 -> HeaderViewHolder(ItemRequestsHeaderBinding.inflate(inflater, parent, false))
            else -> NotConnectedViewHolder(ItemDateHeaderBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ContactItem.User -> {
                (holder as ContactViewHolder).bind(item.contact, item.isMuted, item.contact.account == highlightedAccount)
                holder.itemView.setOnClickListener { onClick(item.contact) }
                holder.itemView.setOnLongClickListener { 
                    onLongClick(it, item.contact)
                    true
                }
            }
            is ContactItem.GroupHeader -> {
                (holder as GroupViewHolder).bind(item.group, item.onlineCount, item.totalCount, item.isExpanded)
                holder.itemView.setOnClickListener { onGroupClick(item.group) }
                holder.itemView.setOnLongClickListener {
                    onGroupLongClick(it, item.group)
                    true
                }
            }
            is ContactItem.Header -> {
                (holder as HeaderViewHolder).bind(item.count)
                holder.itemView.setOnClickListener { onRequestsClick() }
            }
            is ContactItem.NotConnected -> {
                (holder as NotConnectedViewHolder).bind()
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val item = getItem(position)
            if (item is ContactItem.User && holder is ContactViewHolder) {
                payloads.forEach { payload ->
                    if (payload is Bundle) {
                        if (payload.containsKey("status")) {
                            holder.updateStatus(payload.getString("status"), animate = true)
                        }
                        if (payload.containsKey("nickname") || payload.containsKey("psm")) {
                            holder.updateText(item.contact)
                        }
                        if (payload.containsKey("avatar")) {
                            holder.updateAvatar(item.contact)
                        }
                        if (payload.containsKey("mute")) {
                            holder.updateMuteState(payload.getBoolean("mute"))
                        }
                    }
                }
            }
        }
    }

    fun setHighlightedAccount(account: String?) {
        val old = highlightedAccount
        highlightedAccount = account
        
        val oldPos = currentList.indexOfFirst { it is ContactItem.User && it.contact.account == old }
        if (oldPos != -1) notifyItemChanged(oldPos)
        
        val newPos = currentList.indexOfFirst { it is ContactItem.User && it.contact.account == account }
        if (newPos != -1) notifyItemChanged(newPos)
    }

    class ContactViewHolder(private val binding: ItemUserRowBinding) : RecyclerView.ViewHolder(binding.root) {
        private var lastStatus: String? = null

        fun bind(contact: Contact, isMuted: Boolean, isHighlighted: Boolean) {
            updateText(contact)
            updateStatus(contact.status, animate = false)
            updateAvatar(contact)
            updateMuteState(isMuted)
            
            if (isHighlighted) {
                binding.root.setBackgroundResource(android.R.color.holo_blue_light)
            } else {
                binding.root.setBackgroundResource(0)
            }
        }

        fun updateText(contact: Contact) {
            binding.tvTitle.text = FormattingUtils.formatBBCode(contact.nickname.ifEmpty { contact.account })
            val statusStr = itemView.context.getString(StatusUtils.getStatusStringRes(contact.status))
            val psm = contact.personalMessage
            binding.tvSubtitle.text = if (!psm.isNullOrEmpty()) {
                "$statusStr - ${FormattingUtils.formatBBCode(psm)}"
            } else {
                statusStr
            }
        }

        fun updateStatus(status: String?, animate: Boolean) {
            val statusColor = StatusUtils.getStatusColor(status)
            if (animate && lastStatus != null && lastStatus != status) {
                AnimationHelper.animateStatusChange(binding.avatarStatusView.statusView, statusColor)
            } else {
                binding.avatarStatusView.statusView.animate().cancel()
                binding.avatarStatusView.setStatusColor(statusColor)
                binding.avatarStatusView.statusView.scaleX = 1f
                binding.avatarStatusView.statusView.scaleY = 1f
            }
            lastStatus = status
        }

        fun updateAvatar(contact: Contact) {
            binding.avatarStatusView.setAvatar(contact.avatarUrl, contact.account)
        }

        fun updateMuteState(isMuted: Boolean) {
            binding.ivMuted.visibility = if (isMuted) View.VISIBLE else View.GONE
        }
    }

    class GroupViewHolder(private val binding: ItemContactGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(group: Group, onlineCount: Int, totalCount: Int, isExpanded: Boolean) {
            binding.tvGroupName.text = group.name
            binding.tvGroupCount.text = itemView.context.getString(R.string.group_count_format, onlineCount, totalCount)
            binding.ivExpand.rotation = if (isExpanded) 0f else -90f
        }
    }

    class HeaderViewHolder(private val binding: ItemRequestsHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(count: Int) {
            binding.tvRequestsCount.text = count.toString()
            binding.tvRequestsCount.visibility = if (count > 0) View.VISIBLE else View.GONE
        }
    }

    class NotConnectedViewHolder(private val binding: ItemDateHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.tvDateHeader.text = itemView.context.getString(R.string.not_connected_message)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ContactItem>() {
        override fun areItemsTheSame(oldItem: ContactItem, newItem: ContactItem): Boolean {
            return when {
                oldItem is ContactItem.User && newItem is ContactItem.User -> 
                    oldItem.contact.account.lowercase(Locale.ROOT) == newItem.contact.account.lowercase(Locale.ROOT)
                oldItem is ContactItem.GroupHeader && newItem is ContactItem.GroupHeader ->
                    oldItem.group.guid == newItem.group.guid
                oldItem is ContactItem.Header && newItem is ContactItem.Header -> true
                oldItem is ContactItem.NotConnected && newItem is ContactItem.NotConnected -> true
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: ContactItem, newItem: ContactItem): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: ContactItem, newItem: ContactItem): Any? {
            if (oldItem is ContactItem.User && newItem is ContactItem.User) {
                val diff = Bundle()
                if (oldItem.contact.status != newItem.contact.status) diff.putString("status", newItem.contact.status)
                if (oldItem.contact.nickname != newItem.contact.nickname) diff.putString("nickname", newItem.contact.nickname)
                if (oldItem.contact.personalMessage != newItem.contact.personalMessage) diff.putString("psm", newItem.contact.personalMessage)
                if (oldItem.contact.avatarUrl != newItem.contact.avatarUrl) diff.putString("avatar", newItem.contact.avatarUrl)
                if (oldItem.isMuted != newItem.isMuted) diff.putBoolean("mute", newItem.isMuted)
                return if (diff.isEmpty) null else diff
            }
            return null
        }
    }
}
