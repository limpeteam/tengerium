package com.limpe.tengerium.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.bundleOf
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
import com.limpe.tengerium.databinding.FragmentChatsBinding
import com.limpe.tengerium.databinding.ItemUserRowBinding
import com.limpe.tengerium.domain.model.Contact
import com.limpe.tengerium.util.AnimationHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

class ChatsFragment : Fragment() {

    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!
    private val mainViewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory((requireActivity().application as TengeriumApp).repository)
    }
    
    private var isScrollRestored = false

    data class ChatDisplayModel(
        val lastMessage: MessageEntity,
        val contact: Contact?,
        val otherAccount: String,
        val isGroup: Boolean = false
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): android.view.View {
        _binding = FragmentChatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val repository = (requireActivity().application as TengeriumApp).repository
        val adapter = ChatsAdapter(repository) { chat ->
            val mainFragment = parentFragment as? MainFragment
            if (mainFragment != null && mainFragment.isTablet) {
                mainFragment.openChatOnTablet(chat.otherAccount)
            } else {
                val bundle = bundleOf("account" to chat.otherAccount)
                findNavController().navigate(R.id.action_MainFragment_to_ChatFragment, bundle)
            }
        }

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
                    repository.contacts
                ) { chats, contacts ->
                    chats.map { msg ->
                        val otherAccount = if (msg.senderAccount == "me") msg.receiverAccount else msg.senderAccount
                        val isGroup = otherAccount.startsWith("SB_") || msg.receiverAccount.startsWith("SB_")
                        val contact = if (isGroup) null else contacts.find { it.account.lowercase(Locale.ROOT) == otherAccount.lowercase(Locale.ROOT) }
                        ChatDisplayModel(msg, contact, otherAccount, isGroup)
                    }
                }.collectLatest { models ->
                    val b = _binding ?: return@collectLatest
                    b.layoutEmpty.visibility = android.view.View.VISIBLE.takeIf { models.isEmpty() } ?: android.view.View.GONE
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
        private val onClick: (ChatDisplayModel) -> Unit
    ) : ListAdapter<ChatDisplayModel, ChatsAdapter.ViewHolder>(DiffCallback) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemUserRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)
            val isLast = position == itemCount - 1
            holder.bind(item, repository, isLast)
            holder.itemView.setOnClickListener { onClick(item) }
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
                            holder.updateLastMessage(model.lastMessage)
                            holder.updateUnread(model.otherAccount, repository)
                        }
                        if (payload.containsKey("nickname")) {
                            holder.updateNickname(model.contact?.nickname ?: model.otherAccount, model.isGroup)
                        }
                        if (payload.containsKey("avatar")) {
                            holder.updateAvatar(model.contact?.avatarUrl, model.otherAccount, model.isGroup)
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

            fun bind(model: ChatDisplayModel, repository: MSNPRepository, isLast: Boolean) {
                binding.divider.visibility = if (isLast) android.view.View.GONE else android.view.View.VISIBLE
                updateNickname(model.contact?.nickname ?: model.otherAccount, model.isGroup)
                updateLastMessage(model.lastMessage)
                binding.tvTime.visibility = android.view.View.VISIBLE
                binding.tvTime.text = FormattingUtils.formatTime(model.lastMessage.timestamp)
                updateAvatar(model.contact?.avatarUrl, model.otherAccount, model.isGroup)
                updateStatus(if (model.isGroup) "ONLINE" else model.contact?.status, animate = false)
                updateUnread(model.otherAccount, repository)
            }

            fun updateNickname(nickname: String, isGroup: Boolean) {
                binding.tvTitle.text = if (isGroup) {
                    "Групповой чат" // TODO: strings.xml
                } else {
                    FormattingUtils.formatBBCode(nickname)
                }
            }

            fun updateAvatar(url: String?, account: String, isGroup: Boolean) {
                if (isGroup) {
                    binding.ivAvatar.setImageResource(R.drawable.group_24)
                    binding.ivAvatar.background = null
                } else {
                    AvatarUtils.loadAvatar(binding.ivAvatar, url, account)
                }
            }

            fun updateLastMessage(item: MessageEntity) {
                val decrypted = SafeStorage.decryptText(item.encryptedText)
                binding.tvSubtitle.text = when {
                    decrypted == "[NUDGE]" -> itemView.context.getString(if (item.isIncoming) R.string.nudge_received else R.string.nudge_sent_sys)
                    decrypted == "[JOINED]" -> "Присоединился к чату"
                    decrypted == "[LEFT]" -> "Покинул чат"
                    decrypted.startsWith("[GROUP_UPDATE]") -> "Список участников обновлен"
                    else -> FormattingUtils.formatBBCode(decrypted)
                }
                binding.tvTime.text = FormattingUtils.formatTime(item.timestamp)
            }

            fun updateStatus(status: String?, animate: Boolean) {
                val statusColor = StatusUtils.getStatusColor(status)

                if (animate && lastStatus != null && lastStatus != status) {
                    AnimationHelper.animateStatusChange(binding.viewStatus, statusColor)
                } else {
                    binding.viewStatus.animate().cancel()
                    binding.viewStatus.background?.setTint(statusColor)
                    binding.viewStatus.scaleX = 1f
                    binding.viewStatus.scaleY = 1f
                }
                lastStatus = status
            }

            fun updateUnread(otherAccount: String, repository: MSNPRepository) {
                unreadJob?.cancel()
                unreadJob = repository.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                   repository.getUnreadCountForAccount(otherAccount).collectLatest { count ->
                       binding.tvUnreadCount.visibility = if (count > 0) android.view.View.VISIBLE else android.view.View.GONE
                       if (count > 0) binding.tvUnreadCount.text = count.toString()
                   }
                }
            }

            fun cleanup() {
                unreadJob?.cancel()
                unreadJob = null
                lastStatus = null
                binding.viewStatus.animate().cancel()
            }
        }

        object DiffCallback : DiffUtil.ItemCallback<ChatDisplayModel>() {
            override fun areItemsTheSame(oldItem: ChatDisplayModel, newItem: ChatDisplayModel) = 
                oldItem.otherAccount == newItem.otherAccount

            override fun areContentsTheSame(oldItem: ChatDisplayModel, newItem: ChatDisplayModel): Boolean {
                return oldItem.lastMessage.id == newItem.lastMessage.id &&
                       oldItem.lastMessage.timestamp == newItem.lastMessage.timestamp &&
                       oldItem.lastMessage.encryptedText == newItem.lastMessage.encryptedText &&
                       oldItem.contact?.status == newItem.contact?.status &&
                       oldItem.contact?.nickname == newItem.contact?.nickname &&
                       oldItem.contact?.avatarUrl == newItem.contact?.avatarUrl &&
                       oldItem.isGroup == newItem.isGroup
            }

            override fun getChangePayload(oldItem: ChatDisplayModel, newItem: ChatDisplayModel): Any? {
                val diff = Bundle()
                if (oldItem.contact?.status != newItem.contact?.status) {
                    diff.putString("status", newItem.contact?.status)
                }
                if (oldItem.lastMessage.id != newItem.lastMessage.id || 
                    oldItem.lastMessage.encryptedText != newItem.lastMessage.encryptedText) {
                    diff.putBoolean("last_message", true)
                    diff.putBoolean("unread", true)
                }
                if (oldItem.contact?.nickname != newItem.contact?.nickname) {
                    diff.putBoolean("nickname", true)
                }
                if (oldItem.contact?.avatarUrl != newItem.contact?.avatarUrl) {
                    diff.putBoolean("avatar", true)
                }
                return if (diff.isEmpty) null else diff
            }
        }
    }
}
