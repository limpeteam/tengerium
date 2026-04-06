package com.limpe.tengerium.ui

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.MSNPRepository
import com.limpe.tengerium.data.db.MessageEntity
import com.limpe.tengerium.data.security.SafeStorage
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.databinding.FragmentChatsBinding
import com.limpe.tengerium.databinding.ItemUserRowBinding
import com.limpe.tengerium.databinding.LayoutChatMenuBinding
import com.limpe.tengerium.domain.model.Contact
import com.limpe.tengerium.util.AnimationHelper
import com.limpe.tengerium.util.FormattingUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.util.Locale

class ChatsFragment : Fragment() {

    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory((requireActivity().application as TengeriumApp).repository)
    }
    
    private var isScrollRestored = false
    private lateinit var securePrefs: SecurePrefs

    data class ChatDisplayModel(
        val lastMessage: MessageEntity,
        val contact: Contact?,
        val otherAccount: String,
        val isGroup: Boolean = false,
        val isPinned: Boolean = false,
        val isMuted: Boolean = false
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        securePrefs = SecurePrefs(requireContext())

        val repository = (requireActivity().application as TengeriumApp).repository
        val adapter = ChatsAdapter(repository, securePrefs, 
            onClick = { chat ->
                val mainFragment = parentFragment as? MainFragment
                if (mainFragment != null) {
                    mainFragment.openChatOnTablet(chat.otherAccount)
                } else {
                    val bundle = bundleOf("account" to chat.otherAccount)
                    findNavController().navigate(R.id.action_MainFragment_to_ChatFragment, bundle)
                }
            },
            onLongClick = { anchor, chat ->
                showContextMenu(anchor, chat, repository)
            }
        )

        binding.rvChats.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChats.adapter = adapter
        binding.rvChats.alpha = 0f
        
        (binding.rvChats.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        binding.rvChats.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                val firstVisibleItemView = layoutManager?.getChildAt(0)
                if (firstVisibleItemView != null) {
                    mainViewModel.chatsScrollPosition = layoutManager.findFirstVisibleItemPosition()
                    mainViewModel.chatsScrollOffset = firstVisibleItemView.top
                }
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    repository.getRecentChats(),
                    repository.contacts,
                    securePrefs.prefsChangedFlow.onStart { emit("init") }
                ) { chats, contacts, _ ->
                    chats.map { msg ->
                        val otherAccount = if (msg.senderAccount == "me") msg.receiverAccount else msg.senderAccount
                        val isGroup = otherAccount.startsWith("SB_") || msg.receiverAccount.startsWith("SB_")
                        val contact = if (isGroup) null else contacts.find { it.account.lowercase(Locale.ROOT) == otherAccount.lowercase(Locale.ROOT) }
                        ChatDisplayModel(
                            msg, 
                            contact, 
                            otherAccount, 
                            isGroup,
                            isPinned = securePrefs.isChatPinned(otherAccount),
                            isMuted = securePrefs.isChatMuted(otherAccount)
                        )
                    }.sortedWith(compareByDescending<ChatDisplayModel> { it.isPinned }.thenByDescending { it.lastMessage.timestamp })
                }.collectLatest { models ->
                    val b = _binding ?: return@collectLatest
                    b.layoutEmpty.visibility = View.VISIBLE.takeIf { models.isEmpty() } ?: View.GONE
                    adapter.submitList(models) {
                        val bAsync = _binding ?: return@submitList
                        if (!isScrollRestored) {
                            if (mainViewModel.chatsScrollPosition != 0 || mainViewModel.chatsScrollOffset != 0) {
                                (bAsync.rvChats.layoutManager as? LinearLayoutManager)
                                    ?.scrollToPositionWithOffset(mainViewModel.chatsScrollPosition, mainViewModel.chatsScrollOffset)
                            }
                            bAsync.rvChats.post {
                                _binding?.rvChats?.alpha = 1f
                                isScrollRestored = true
                            }
                        } else {
                            bAsync.rvChats.alpha = 1f
                        }
                    }
                }
            }
        }
    }

    private fun showContextMenu(anchor: View, chat: ChatDisplayModel, repository: MSNPRepository) {
        val menuBinding = LayoutChatMenuBinding.inflate(layoutInflater)
        val popup = PopupWindow(menuBinding.root, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        
        popup.elevation = 20f

        // Pin/Unpin
        val isPinned = securePrefs.isChatPinned(chat.otherAccount)
        menuBinding.btnMenuPin.isVisible = true
        menuBinding.btnMenuPin.text = if (isPinned) getString(R.string.unpin_chat) else getString(R.string.pin_chat)
        menuBinding.btnMenuPin.setIconResource(if (isPinned) R.drawable.ic_unpin else R.drawable.ic_pin)
        menuBinding.btnMenuPin.setOnClickListener {
            securePrefs.togglePinChat(chat.otherAccount)
            popup.dismiss()
        }

        // В чатах Folder и Remove from folder не нужны
        menuBinding.btnMenuFolder.isVisible = false
        menuBinding.btnMenuRemoveFolder.isVisible = false

        // Mute/Unmute
        val isMuted = securePrefs.isChatMuted(chat.otherAccount)
        menuBinding.btnMenuMute.text = if (isMuted) getString(R.string.unmute_notifications) else getString(R.string.mute_notifications)
        menuBinding.btnMenuMute.setIconResource(if (isMuted) R.drawable.ic_notifications_on else R.drawable.ic_notifications_off)
        menuBinding.btnMenuMute.setOnClickListener {
            securePrefs.toggleMuteChat(chat.otherAccount)
            popup.dismiss()
        }

        // Block/Unblock
        val isBlocked = chat.contact?.listType?.contains("BL") ?: false
        menuBinding.btnMenuBlock.text = if (isBlocked) getString(R.string.unblock) else getString(R.string.block)
        menuBinding.btnMenuBlock.setIconResource(if (isBlocked) R.drawable.ic_unblock else R.drawable.ic_block)
        menuBinding.btnMenuBlock.setOnClickListener {
            if (isBlocked) repository.unblockContact(chat.otherAccount) else repository.blockContact(chat.otherAccount)
            popup.dismiss()
        }

        menuBinding.root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val xOffset = -menuBinding.root.measuredWidth / 2 + anchor.width / 2
        val yOffset = -anchor.height / 2
        
        AnimationHelper.showSmartPopup(popup, anchor, xOffset, yOffset)
    }

    override fun onDestroyView() {
        _binding?.let { b ->
            val layoutManager = b.rvChats.layoutManager as? LinearLayoutManager
            val firstVisibleItemView = layoutManager?.getChildAt(0)
            if (firstVisibleItemView != null) {
                mainViewModel.chatsScrollPosition = layoutManager.findFirstVisibleItemPosition()
                mainViewModel.chatsScrollOffset = firstVisibleItemView.top
            }
        }
        super.onDestroyView()
        _binding = null
    }

    class ChatsAdapter(
        private val repository: MSNPRepository,
        private val securePrefs: SecurePrefs,
        private val onClick: (ChatDisplayModel) -> Unit,
        private val onLongClick: (View, ChatDisplayModel) -> Unit
    ) : ListAdapter<ChatDisplayModel, ChatsAdapter.ViewHolder>(DiffCallback) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemUserRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)
            val isLast = position == itemCount - 1
            holder.bind(item, repository, isLast, securePrefs)
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnCreateContextMenuListener(null)
            holder.itemView.setOnLongClickListener {
                onLongClick(holder.itemView, item)
                true
            }
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                super.onBindViewHolder(holder, position, payloads)
            } else {
                val model = getItem(position)
                payloads.forEach { payload ->
                    if (payload is Bundle) {
                        if (payload.containsKey("status")) {
                            holder.updateStatus(payload.getString("status"), animate = true)
                        }
                        if (payload.containsKey("unread") || payload.containsKey("last_message")) {
                            holder.updateLastMessage(model.lastMessage, model.isPinned)
                            holder.updateUnread(model.otherAccount, repository)
                        }
                        if (payload.containsKey("nickname")) {
                            holder.updateNickname(model.contact?.nickname ?: model.otherAccount, model.isGroup)
                        }
                        if (payload.containsKey("avatar")) {
                            holder.updateAvatar(model.contact?.avatarUrl, model.otherAccount, model.isGroup)
                        }
                        if (payload.containsKey("pin")) {
                            holder.updatePinState(model.isPinned)
                        }
                        if (payload.containsKey("mute")) {
                            holder.updateMuteState(model.otherAccount, securePrefs)
                        }
                    }
                }
            }
        }

        override fun onViewRecycled(holder: ViewHolder) {
            super.onViewRecycled(holder)
            holder.cleanup()
        }

        class ViewHolder(private val binding: ItemUserRowBinding) : RecyclerView.ViewHolder(binding.root) {
            private var unreadJob: Job? = null
            private var lastStatus: String? = null

            fun bind(model: ChatDisplayModel, repository: MSNPRepository, isLast: Boolean, securePrefs: SecurePrefs) {
                binding.divider.visibility = if (isLast) View.GONE else View.VISIBLE
                updateNickname(model.contact?.nickname ?: model.otherAccount, model.isGroup)
                updateLastMessage(model.lastMessage, model.isPinned)
                updateAvatar(model.contact?.avatarUrl, model.otherAccount, model.isGroup)
                updateStatus(if (model.isGroup) "ONLINE" else model.contact?.status, animate = false)
                updateUnread(model.otherAccount, repository)
                updatePinState(model.isPinned)
                updateMuteState(model.otherAccount, securePrefs)
            }

            fun updateNickname(nickname: String, isGroup: Boolean) {
                binding.tvTitle.text = if (isGroup) {
                    itemView.context.getString(R.string.group_chat)
                } else {
                    FormattingUtils.formatBBCode(nickname)
                }
            }

            fun updateAvatar(url: String?, account: String, isGroup: Boolean) {
                binding.avatarStatusView.setAvatar(url, account, isGroup)
            }

            fun updateLastMessage(item: MessageEntity, isPinned: Boolean) {
                val context = itemView.context
                val decrypted = SafeStorage.decryptText(item.encryptedText)

                if (decrypted == "[NUDGE]") {
                    val text = context.getString(if (item.isIncoming) R.string.nudge_received else R.string.nudge_sent_sys)
                    val spannable = SpannableStringBuilder("  $text")
                    val drawable = ContextCompat.getDrawable(context, R.drawable.ic_nudge_menu)?.apply {
                        val size = binding.tvSubtitle.textSize.toInt()
                        setBounds(0, 0, size, size)
                        setTint(binding.tvSubtitle.currentTextColor)
                    }
                    if (drawable != null) {
                        spannable.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    binding.tvSubtitle.text = spannable
                } else {
                    val body = when {
                        decrypted == "[JOINED]" -> context.getString(R.string.user_joined_chat, item.senderAccount)
                        decrypted == "[LEFT]" -> context.getString(R.string.user_left_chat, item.senderAccount)
                        decrypted.startsWith("[GROUP_UPDATE]") -> context.getString(R.string.group_participants_updated)
                        else -> FormattingUtils.formatBBCode(decrypted)
                    }

                    binding.tvSubtitle.text = if (!item.isIncoming && !decrypted.startsWith("[")) {
                        context.getString(R.string.chat_me_prefix, body)
                    } else {
                        body
                    }
                }

                binding.tvTime.visibility = View.VISIBLE
                binding.tvTime.text = FormattingUtils.formatChatDate(context, item.timestamp)

                binding.pbPending.visibility = if (!item.isIncoming && !item.isSent && item.error == null) View.VISIBLE else View.GONE
            }

            fun updatePinState(isPinned: Boolean) {
                binding.ivPinned.visibility = if (isPinned) View.VISIBLE else View.GONE
            }

            fun updateMuteState(account: String, securePrefs: SecurePrefs) {
                binding.ivMuted.visibility = if (securePrefs.isChatMuted(account)) View.VISIBLE else View.GONE
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

            fun updateUnread(otherAccount: String, repository: MSNPRepository) {
                unreadJob?.cancel()
                unreadJob = repository.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                   repository.getUnreadCountForAccount(otherAccount).collectLatest { count ->
                       binding.tvUnreadCount.visibility = if (count > 0) View.VISIBLE else View.GONE
                       if (count > 0) binding.tvUnreadCount.text = count.toString()
                   }
                }
            }

            fun cleanup() {
                unreadJob?.cancel()
                unreadJob = null
                lastStatus = null
                binding.avatarStatusView.statusView.animate().cancel()
            }
        }

        object DiffCallback : DiffUtil.ItemCallback<ChatDisplayModel>() {
            override fun areItemsTheSame(oldItem: ChatDisplayModel, newItem: ChatDisplayModel) =
                oldItem.otherAccount == newItem.otherAccount

            override fun areContentsTheSame(oldItem: ChatDisplayModel, newItem: ChatDisplayModel): Boolean {
                return oldItem.lastMessage.id == newItem.lastMessage.id &&
                       oldItem.lastMessage.timestamp == newItem.lastMessage.timestamp &&
                       oldItem.lastMessage.encryptedText == newItem.lastMessage.encryptedText &&
                       oldItem.lastMessage.isSent == newItem.lastMessage.isSent &&
                       oldItem.lastMessage.error == newItem.lastMessage.error &&
                       oldItem.contact?.status == newItem.contact?.status &&
                       oldItem.contact?.nickname == newItem.contact?.nickname &&
                       oldItem.contact?.avatarUrl == newItem.contact?.avatarUrl &&
                       oldItem.isGroup == newItem.isGroup &&
                       oldItem.isPinned == newItem.isPinned &&
                       oldItem.isMuted == newItem.isMuted
            }

            override fun getChangePayload(oldItem: ChatDisplayModel, newItem: ChatDisplayModel): Any? {
                val diff = Bundle()
                if (oldItem.contact?.status != newItem.contact?.status) {
                    diff.putString("status", newItem.contact?.status)
                }
                if (oldItem.lastMessage.id != newItem.lastMessage.id ||
                    oldItem.lastMessage.encryptedText != newItem.lastMessage.encryptedText ||
                    oldItem.lastMessage.isSent != newItem.lastMessage.isSent ||
                    oldItem.lastMessage.error != newItem.lastMessage.error) {
                    diff.putBoolean("last_message", true)
                    diff.putBoolean("unread", true)
                }
                if (oldItem.contact?.nickname != newItem.contact?.nickname) {
                    diff.putBoolean("nickname", true)
                }
                if (oldItem.contact?.avatarUrl != newItem.contact?.avatarUrl) {
                    diff.putBoolean("avatar", true)
                }
                if (oldItem.isPinned != newItem.isPinned) {
                    diff.putBoolean("pin", true)
                }
                if (oldItem.isMuted != newItem.isMuted) {
                    diff.putBoolean("mute", true)
                }
                return if (diff.isEmpty) null else diff
            }
        }
    }
}
