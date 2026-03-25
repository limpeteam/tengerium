package com.limpe.tengerium.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
import com.limpe.tengerium.util.LinkPreview
import com.limpe.tengerium.util.LinkPreviewHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class ChatAdapter(private val scope: CoroutineScope) : ListAdapter<MessageEntity, RecyclerView.ViewHolder>(DiffCallback()) {

    private var securePrefs: SecurePrefs? = null

    companion object {
        private const val VIEW_TYPE_MESSAGE = 0
        private const val VIEW_TYPE_SYSTEM = 1
    }

    override fun getItemViewType(position: Int): Int {
        val message = getItem(position)
        val decryptedText = SafeStorage.decryptText(message.encryptedText)
        return if (decryptedText == "[NUDGE]" || decryptedText == "[JOINED]" || decryptedText == "[LEFT]" || decryptedText.startsWith("[GROUP_UPDATE]")) {
            VIEW_TYPE_SYSTEM
        } else {
            VIEW_TYPE_MESSAGE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (securePrefs == null) {
            securePrefs = SecurePrefs(parent.context)
        }
        
        return if (viewType == VIEW_TYPE_SYSTEM) {
            val binding = ItemDateHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            SystemViewHolder(binding, securePrefs!!)
        } else {
            val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            MessageViewHolder(binding, scope, securePrefs!!)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        if (holder is SystemViewHolder) {
            holder.bind(message)
        } else if (holder is MessageViewHolder) {
            holder.bind(message)
        }
    }

    class MessageViewHolder(
        private val binding: ItemMessageBinding, 
        private val scope: CoroutineScope,
        private val securePrefs: SecurePrefs
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

            val params = binding.cardMessage.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            
            val useMaterialYou = securePrefs.useMaterialYou

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

            binding.cardMessage.strokeWidth = 0
            binding.cardMessage.cardElevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, context.resources.displayMetrics)
            
            if (useMaterialYou) {
                val colorAttr = if (message.isIncoming) com.google.android.material.R.attr.colorSecondaryContainer else com.google.android.material.R.attr.colorPrimaryContainer
                val onColorAttr = if (message.isIncoming) com.google.android.material.R.attr.colorOnSecondaryContainer else com.google.android.material.R.attr.colorOnPrimaryContainer
                
                val color = MaterialColors.getColor(context, colorAttr, 0)
                val onColor = MaterialColors.getColor(context, onColorAttr, 0)
                
                binding.cardMessage.setCardBackgroundColor(color)
                binding.tvMessageBody.setTextColor(onColor)
                binding.tvTimestamp.setTextColor(onColor)
                binding.tvMessageBody.setLinkTextColor(onColor)
            } else {
                val preset = ThemePreset.presets.getOrNull(securePrefs.themePreset) ?: ThemePreset.presets[0]
                val bubbleColor = if (message.isIncoming) preset.incomingBubbleColor else preset.outgoingBubbleColor
                val textColor = if (message.isIncoming) preset.onIncomingTextColor else preset.onOutgoingTextColor
                
                binding.cardMessage.setCardBackgroundColor(bubbleColor)
                binding.tvMessageBody.setTextColor(textColor)
                binding.tvTimestamp.setTextColor(textColor)
                binding.tvMessageBody.setLinkTextColor(textColor)
            }
            binding.tvTimestamp.alpha = 0.6f

            binding.pbSending.visibility = if (!message.isIncoming && !message.isSent && message.error == null) View.VISIBLE else View.GONE
            binding.tvError.visibility = if (message.error != null) android.view.View.VISIBLE else android.view.View.GONE
            binding.tvError.text = message.error

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
            
            binding.tvDateHeader.text = when {
                decryptedText == "[NUDGE]" -> {
                    if (message.isIncoming) context.getString(R.string.nudge_received) else context.getString(R.string.nudge_sent_sys)
                }
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

    class DiffCallback : DiffUtil.ItemCallback<MessageEntity>() {
        override fun areItemsTheSame(oldItem: MessageEntity, newItem: MessageEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: MessageEntity, newItem: MessageEntity): Boolean {
            return oldItem == newItem
        }
    }
}
