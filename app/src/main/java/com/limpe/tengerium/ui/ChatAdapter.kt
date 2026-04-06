package com.limpe.tengerium.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.color.MaterialColors
import com.limpe.tengerium.R
import com.limpe.tengerium.data.ThemePreset
import com.limpe.tengerium.data.db.MessageEntity
import com.limpe.tengerium.databinding.ItemDateHeaderBinding
import com.limpe.tengerium.databinding.ItemMessageBinding
import com.limpe.tengerium.data.security.SafeStorage
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.util.AnimationHelper
import com.limpe.tengerium.util.BubbleStyleHelper
import com.limpe.tengerium.util.FormattingUtils
import com.limpe.tengerium.util.LinkPreview
import com.limpe.tengerium.util.LinkPreviewHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

sealed class ChatDataItem {
    data class Message(val message: MessageEntity) : ChatDataItem()
    data class DateHeader(val timestamp: Long) : ChatDataItem()
    data class System(val message: MessageEntity) : ChatDataItem()

    val itemId: Long
        get() = when (this) {
            is Message -> message.id
            is DateHeader -> -timestamp // Используем отрицательный timestamp для уникальности
            is System -> message.id
        }
}

class ChatAdapter(
    private val scope: CoroutineScope,
    private val onLongClick: (MessageEntity) -> Unit = {}
) : ListAdapter<ChatDataItem, RecyclerView.ViewHolder>(DiffCallback()) {

    private var securePrefs: SecurePrefs? = null

    companion object {
        private const val VIEW_TYPE_MESSAGE = 0
        private const val VIEW_TYPE_SYSTEM = 1
        private const val VIEW_TYPE_DATE_HEADER = 2
    }

    fun submitMessages(messages: List<MessageEntity>, commitCallback: (() -> Unit)? = null) {
        val items = mutableListOf<ChatDataItem>()
        if (messages.isEmpty()) {
            submitList(emptyList(), commitCallback)
            return
        }

        var lastDate = ""
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())

        messages.forEach { msg ->
            val msgDate = sdf.format(Date(msg.timestamp))
            if (msgDate != lastDate) {
                items.add(ChatDataItem.DateHeader(msg.timestamp))
                lastDate = msgDate
            }

            val decryptedText = SafeStorage.decryptText(msg.encryptedText)
            if (decryptedText == "[NUDGE]" || decryptedText == "[JOINED]" || decryptedText == "[LEFT]" || decryptedText.startsWith("[GROUP_UPDATE]")) {
                items.add(ChatDataItem.System(msg))
            } else {
                items.add(ChatDataItem.Message(msg))
            }
        }
        submitList(items, commitCallback)
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ChatDataItem.Message -> VIEW_TYPE_MESSAGE
            is ChatDataItem.System -> VIEW_TYPE_SYSTEM
            is ChatDataItem.DateHeader -> VIEW_TYPE_DATE_HEADER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (securePrefs == null) {
            securePrefs = SecurePrefs(parent.context)
        }
        
        return when (viewType) {
            VIEW_TYPE_DATE_HEADER -> {
                val binding = ItemDateHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                DateHeaderViewHolder(binding)
            }
            VIEW_TYPE_SYSTEM -> {
                val binding = ItemDateHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                SystemViewHolder(binding, securePrefs!!)
            }
            else -> {
                val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                MessageViewHolder(binding, scope, securePrefs!!, onLongClick)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is DateHeaderViewHolder -> (item as? ChatDataItem.DateHeader)?.let { holder.bind(it.timestamp) }
            is SystemViewHolder -> (item as? ChatDataItem.System)?.let { holder.bind(it.message) }
            is MessageViewHolder -> (item as? ChatDataItem.Message)?.let { holder.bind(it.message) }
        }
    }

    class DateHeaderViewHolder(private val binding: ItemDateHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(timestamp: Long) {
            binding.tvDateHeader.text = FormattingUtils.formatDateHeader(itemView.context, timestamp)
            binding.ivIcon.visibility = View.GONE
            
            val context = itemView.context
            val surfaceColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, 0)
            val onSurfaceColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
            
            binding.cardDateHeader.apply {
                setCardBackgroundColor(surfaceColor)
                strokeWidth = 0
                cardElevation = 0f
            }
            binding.tvDateHeader.setTextColor(onSurfaceColor)
        }
    }

    class MessageViewHolder(
        private val binding: ItemMessageBinding, 
        private val scope: CoroutineScope,
        private val securePrefs: SecurePrefs,
        private val onLongClick: (MessageEntity) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        init {
            binding.tvMessageBody.movementMethod = LinkMovementMethod.getInstance()
        }

        fun bind(message: MessageEntity) {
            val decryptedText = SafeStorage.decryptText(message.encryptedText)
            val context = itemView.context

            binding.tvMessageBody.text = decryptedText
            binding.tvTimestamp.text = timeFormat.format(Date(message.timestamp))
            
            Linkify.addLinks(binding.tvMessageBody, Linkify.WEB_URLS)

            binding.root.setOnLongClickListener {
                onLongClick(message)
                true
            }

            val params = binding.cardMessage.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            
            if (message.isIncoming) {
                params.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                params.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                params.horizontalBias = 0f
            } else {
                params.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                params.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                params.horizontalBias = 1f
            }
            
            val halfScreenWidth = (context.resources.displayMetrics.widthPixels * 0.5).toInt()
            params.width = 0 
            params.matchConstraintDefaultWidth = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_WRAP
            params.matchConstraintMaxWidth = halfScreenWidth
            binding.cardMessage.layoutParams = params

            BubbleStyleHelper.applyStyle(
                binding.cardMessage,
                binding.tvMessageBody,
                binding.tvTimestamp,
                message.isIncoming,
                securePrefs
            )

            binding.pbSending.visibility = if (!message.isIncoming && !message.isSent && message.error == null) View.VISIBLE else View.GONE
            binding.tvError.visibility = if (message.error != null) android.view.View.VISIBLE else android.view.View.GONE
            binding.tvError.text = if (message.error != null) context.getString(R.string.resend) else null

            // Animation for new messages
            val isNewMessage = (System.currentTimeMillis() - message.timestamp) < 1000
            if (isNewMessage && binding.cardMessage.tag != message.id) {
                binding.cardMessage.tag = message.id
                AnimationHelper.animateMessagePop(binding.cardMessage)
            } else {
                binding.cardMessage.tag = message.id
                binding.cardMessage.alpha = 1f
                binding.cardMessage.scaleX = 1f
                binding.cardMessage.scaleY = 1f
            }

            if (securePrefs.showLinkPreview) {
                val url = LinkPreviewHelper.extractUrl(decryptedText)
                if (url != null) {
                    binding.cardLinkPreview.setOnClickListener {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                        } catch (e: Exception) {}
                    }
                    
                    scope.launch {
                        val preview = LinkPreviewHelper.fetchPreview(url)
                        withContext(Dispatchers.Main) {
                            if (preview != null) {
                                showLinkPreview(preview, halfScreenWidth)
                            } else {
                                binding.cardLinkPreview.visibility = View.GONE
                            }
                        }
                    }
                } else {
                    binding.cardLinkPreview.visibility = View.GONE
                }
            } else {
                binding.cardLinkPreview.visibility = View.GONE
            }
        }

        private fun showLinkPreview(preview: LinkPreview, maxWidth: Int) {
            binding.cardLinkPreview.visibility = View.VISIBLE
            binding.tvLinkTitle.text = preview.title
            binding.tvLinkDesc.text = preview.description
            binding.tvLinkDomain.text = preview.domain
            
            val context = binding.root.context
            val density = context.resources.displayMetrics.density
            
            val targetWidthPx = (213 * density).toInt()
            val bubblePadding = (12 * 2 * density).toInt()
            val finalWidth = minOf(targetWidthPx, maxWidth - bubblePadding)

            binding.cardLinkPreview.layoutParams = binding.cardLinkPreview.layoutParams.apply {
                width = finalWidth
            }

            if (!preview.imageUrl.isNullOrEmpty()) {
                binding.ivLinkImage.visibility = View.VISIBLE
                binding.ivLinkImage.load(preview.imageUrl) {
                    crossfade(true)
                }
            } else {
                binding.ivLinkImage.visibility = View.GONE
            }

            if (preview.description.isNullOrEmpty()) {
                binding.tvLinkDesc.visibility = View.GONE
            } else {
                binding.tvLinkDesc.visibility = View.VISIBLE
            }
        }
    }

    class SystemViewHolder(
        private val binding: ItemDateHeaderBinding,
        private val securePrefs: SecurePrefs
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: MessageEntity) {
            val context = itemView.context
            val decryptedText = SafeStorage.decryptText(message.encryptedText)
            
            if (decryptedText == "[NUDGE]") {
                binding.ivIcon.visibility = View.VISIBLE
                binding.ivIcon.setImageResource(R.drawable.ic_nudge_menu)
                binding.tvDateHeader.text = if (message.isIncoming) context.getString(R.string.nudge_received) else context.getString(R.string.nudge_sent_sys)
            } else {
                binding.ivIcon.visibility = View.GONE
                binding.tvDateHeader.text = when {
                    decryptedText == "[JOINED]" -> {
                        context.getString(R.string.user_joined_chat, message.senderAccount)
                    }
                    decryptedText == "[LEFT]" -> {
                        context.getString(R.string.user_left_chat, message.senderAccount)
                    }
                    decryptedText.startsWith("[GROUP_UPDATE]") -> {
                        val participants = decryptedText.substringAfter("|")
                        participants
                    }
                    else -> decryptedText
                }
            }

            val surfaceColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, 0)
            val onSurfaceColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
            
            binding.cardDateHeader.apply {
                setCardBackgroundColor(surfaceColor)
                strokeWidth = 0
                cardElevation = 0f
            }
            binding.tvDateHeader.setTextColor(onSurfaceColor)
            binding.tvDateHeader.alpha = 1.0f
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ChatDataItem>() {
        override fun areItemsTheSame(oldItem: ChatDataItem, newItem: ChatDataItem): Boolean {
            return oldItem.itemId == newItem.itemId
        }

        override fun areContentsTheSame(oldItem: ChatDataItem, newItem: ChatDataItem): Boolean {
            return oldItem == newItem
        }
    }
}
