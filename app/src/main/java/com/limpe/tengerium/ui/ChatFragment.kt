package com.limpe.tengerium.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.MSNPLoginState
import com.limpe.tengerium.data.MSNPRepository
import com.limpe.tengerium.data.protocol.MSNPProto
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.databinding.FragmentChatBinding
import com.limpe.tengerium.util.AnimationHelper
import com.limpe.tengerium.util.ChatHistoryExporter
import com.limpe.tengerium.util.FormattingUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.*

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: MSNPRepository
    private lateinit var securePrefs: SecurePrefs
    private lateinit var adapter: ChatAdapter
    private var contactAccount: String? = null
    
    private var typingJob: Job? = null
    private var lastStatus: String? = null
    
    private val mainViewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory((requireActivity().application as TengeriumApp).repository)
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            performExport(uri)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = (requireActivity().application as TengeriumApp).repository
        securePrefs = SecurePrefs(requireContext())
        contactAccount = arguments?.getString("account")

        setupUI()
        observeData()
        
        mainViewModel.setActiveChat(contactAccount)
    }

    private fun setupUI() {
        adapter = ChatAdapter(viewLifecycleOwner.lifecycleScope, onLongClick = { message ->
            if (!message.isIncoming && message.error != null) {
                repository.resendMessage(message.id)
            }
        })
        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvMessages.adapter = adapter
        
        applyDynamicDesign()
        loadChatBackground()

        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        binding.toolbar.inflateMenu(R.menu.menu_chat)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_send_nudge -> {
                    sendNudge()
                    true
                }
                R.id.action_export_chat -> {
                    startExport()
                    true
                }
                else -> false
            }
        }

        binding.layoutChatHeader.setOnClickListener { openProfile() }

        binding.btnSend.setOnClickListener { sendMessage() }
        
        binding.etMessage.addTextChangedListener { s ->
            if (!s.isNullOrEmpty()) {
                repository.sendTyping(contactAccount ?: "")
            }
        }

        if (securePrefs.sendByEnter) {
            binding.etMessage.imeOptions = EditorInfo.IME_ACTION_SEND
            binding.etMessage.setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        }

        binding.etMessage.setOnEditorActionListener { _, actionId, event ->
            if (securePrefs.sendByEnter) {
                if (actionId == EditorInfo.IME_ACTION_SEND || 
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    sendMessage()
                    return@setOnEditorActionListener true
                }
            }
            false
        }

        binding.etMessage.setOnKeyListener { _, keyCode, event ->
            if (securePrefs.sendByEnter && keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                sendMessage()
                true
            } else {
                false
            }
        }

        binding.rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val isAtBottom = lastVisible >= adapter.itemCount - 3
                
                if (isAtBottom) {
                    hideScrollToBottom()
                } else if (dy < -10) {
                    showScrollToBottom()
                }
            }
        })

        binding.btnScrollToBottom.setOnClickListener {
            binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun startExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "chat_history_${contactAccount?.replace("@", "_")}.json")
        }
        exportLauncher.launch(intent)
    }

    private fun performExport(uri: Uri) {
        val account = contactAccount ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val messages = repository.getMessages(account).first()
            val contacts = repository.contacts.value
            val contact = contacts.find { it.account.equals(account, ignoreCase = true) }
            
            val loginState = repository.loginState.value as? MSNPLoginState.Success
            val myNickname = loginState?.nickname
            val myEmail = repository.getCurrentAccount() ?: ""

            val success = ChatHistoryExporter.exportChat(
                requireContext(),
                uri,
                contact?.nickname,
                account,
                myNickname,
                myEmail,
                messages
            )
            
            if (success) {
                Toast.makeText(requireContext(), R.string.export_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.export_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadChatBackground() {
        val bgPath = securePrefs.chatBackgroundPath
        if (!bgPath.isNullOrEmpty()) {
            binding.ivChatBackground.isVisible = true
            binding.ivChatBackground.load(Uri.parse(bgPath))
            
            // If background is set, make main background transparent to see the image
            binding.root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            binding.ivChatBackground.isVisible = false
        }
    }

    private fun sendNudge() {
        contactAccount?.let { account ->
            repository.sendNudge(account)
        }
    }

    private fun openProfile() {
        contactAccount?.let { account ->
            val navController = findNavController()
            if (navController.currentDestination?.id != R.id.ChatFragment) return
            
            val bundle = bundleOf("account" to account)
            val parent = parentFragment
            if (parent is MainFragment && parent.isTablet) {
                val profileFragment = ProfileFragment().apply {
                    arguments = bundle
                }
                parent.showDetail(profileFragment)
            } else {
                navController.navigate(R.id.action_ChatFragment_to_ProfileFragment, bundle)
            }
        }
    }

    private fun applyDynamicDesign() {
        val surfaceColor = com.google.android.material.color.MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorSurface, 0)
        
        if (securePrefs.chatBackgroundPath.isNullOrEmpty()) {
            binding.root.setBackgroundColor(surfaceColor)
        }

        binding.appBarLayout.setBackgroundColor(surfaceColor)
        binding.layoutInput.setBackgroundColor(surfaceColor)
        binding.toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        
        binding.appBarLayout.elevation = 0f
        binding.layoutInput.elevation = 4f
    }

    private fun showScrollToBottom() {
        if (binding.btnScrollToBottom.visibility == View.VISIBLE) return
        binding.btnScrollToBottom.visibility = View.VISIBLE
        binding.btnScrollToBottom.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .setInterpolator(OvershootInterpolator())
            .start()
    }

    private fun hideScrollToBottom() {
        if (binding.btnScrollToBottom.visibility == View.GONE) return
        binding.btnScrollToBottom.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(200)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    _binding?.btnScrollToBottom?.visibility = View.GONE
                }
            })
            .start()
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isNotEmpty() && contactAccount != null) {
            repository.sendMessage(contactAccount!!, text)
            binding.etMessage.text.clear()
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                contactAccount?.let { account ->
                    val normalizedAccount = account.lowercase(Locale.ROOT).trim()
                    
                    launch {
                        repository.getMessages(account).collectLatest { messages ->
                            binding.layoutEmpty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                            adapter.submitList(messages) {
                                if (messages.isNotEmpty()) {
                                    binding.rvMessages.scrollToPosition(messages.size - 1)
                                }
                            }
                        }
                    }

                    launch {
                        repository.contacts.collectLatest { contacts ->
                            val contact = contacts.find { it.account.equals(account, ignoreCase = true) }
                            binding.tvChatName.text = FormattingUtils.formatBBCode(contact?.nickname ?: account)
                            binding.avatarStatusView.setAvatar(contact?.avatarUrl, account)
                            
                            val status = contact?.status ?: MSNPProto.Status.OFFLINE
                            updateStatusUI(status, contact?.personalMessage)
                            
                            val isFriend = contact != null
                            binding.layoutNotFriend.visibility = if (isFriend) View.GONE else View.VISIBLE

                            // Handle disabled queue blocking input
                            if (securePrefs.disableMessageQueue) {
                                val isOffline = status == MSNPProto.Status.OFFLINE || status == "FLN"
                                if (isOffline) {
                                    binding.etMessage.isEnabled = false
                                    binding.btnSend.isEnabled = false
                                    binding.etMessage.setText(R.string.messaging_unavailable)
                                } else {
                                    if (!binding.etMessage.isEnabled) {
                                        binding.etMessage.isEnabled = true
                                        binding.btnSend.isEnabled = true
                                        binding.etMessage.text.clear()
                                    }
                                }
                            } else {
                                if (!binding.etMessage.isEnabled) {
                                    binding.etMessage.isEnabled = true
                                    binding.btnSend.isEnabled = true
                                    binding.etMessage.text.clear()
                                }
                            }
                        }
                    }
                    
                    launch {
                        repository.contacts
                            .map { list -> list.find { it.account.equals(account, ignoreCase = true) }?.isTyping ?: false }
                            .distinctUntilChanged()
                            .collectLatest { isTyping ->
                                updateTypingIndicator(isTyping)
                            }
                    }

                    launch {
                        repository.nudgeCooldowns.collectLatest { cooldowns ->
                            val secondsLeft = cooldowns[normalizedAccount] ?: 0
                            val nudgeItem = binding.toolbar.menu.findItem(R.id.action_send_nudge)
                            if (secondsLeft > 0) {
                                nudgeItem?.isEnabled = false
                                nudgeItem?.title = getString(R.string.send_nudge_cooldown, secondsLeft)
                            } else {
                                nudgeItem?.isEnabled = true
                                nudgeItem?.title = getString(R.string.send_nudge)
                            }
                        }
                    }
                }
            }
        }
    }
    
    private fun updateTypingIndicator(isTyping: Boolean) {
        val b = _binding ?: return
        typingJob?.cancel()
        if (isTyping) {
            b.tvTypingIndicator.visibility = View.VISIBLE
            b.tvTypingIndicator.animate().alpha(1f).setDuration(200).start()
            
            val baseText = getString(R.string.is_typing)
            typingJob = AnimationHelper.startTypingAnimation(viewLifecycleOwner.lifecycleScope, b.tvTypingIndicator, baseText)
        } else {
            b.tvTypingIndicator.animate().alpha(0f).setDuration(200).withEndAction {
                _binding?.tvTypingIndicator?.visibility = View.GONE
            }.start()
        }
    }

    private fun updateStatusUI(status: String, psm: String?) {
        val colorRes = StatusUtils.getStatusColorRes(status)
        val color = androidx.core.content.ContextCompat.getColor(requireContext(), colorRes)
        
        if (lastStatus != null && lastStatus != status) {
            AnimationHelper.animateStatusChange(binding.avatarStatusView.statusView, color)
        } else {
            binding.avatarStatusView.setStatusColor(color)
        }
        lastStatus = status
        
        binding.tvChatStatus.setTextColor(color)
        
        if (!psm.isNullOrEmpty()) {
            binding.tvChatStatus.text = psm
        } else {
            binding.tvChatStatus.setText(StatusUtils.getStatusStringRes(status))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainViewModel.setActiveChat(null)
        typingJob?.cancel()
        _binding?.tvTypingIndicator?.animate()?.cancel()
        _binding = null
    }
}
