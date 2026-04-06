package com.limpe.tengerium.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import coil.load
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.MSNPRepository
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.databinding.FragmentChatSettingsBinding
import com.limpe.tengerium.util.BubbleStyleHelper
import kotlinx.coroutines.launch

class ChatSettingsFragment : Fragment() {

    private var _binding: FragmentChatSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var securePrefs: SecurePrefs
    private lateinit var repository: MSNPRepository
    
    private val viewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory((requireActivity().application as TengeriumApp).repository)
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            uri?.let { saveChatBackground(it) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        securePrefs = SecurePrefs(requireContext())
        repository = (requireActivity().application as TengeriumApp).repository

        loadSettings()
        setupUI()
        
        val mode = arguments?.getString("mode")
        if (mode != null) {
            updateMode(mode)
        }
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener { performBack() }
        
        binding.swipeBackLayout.setOnSwipeBackListener {
            performBack()
        }

        binding.switchSystemTheme.setOnCheckedChangeListener { _, isChecked ->
            if (securePrefs.followSystemTheme != isChecked) {
                securePrefs.followSystemTheme = isChecked
                binding.switchDarkTheme.isEnabled = !isChecked
                updateTheme()
            }
        }

        binding.switchDarkTheme.setOnCheckedChangeListener { _, isChecked ->
            if (securePrefs.darkTheme != isChecked) {
                securePrefs.darkTheme = isChecked
                updateTheme()
            }
        }

        binding.switchMaterialYou.setOnCheckedChangeListener { _, isChecked ->
            if (securePrefs.useMaterialYou != isChecked) {
                securePrefs.useMaterialYou = isChecked
                updateMaterialYouVisibility(isChecked)
                requireActivity().recreate()
            }
        }
        
        binding.switchOpenChatsByDefault.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.openChatsByDefault = isChecked
        }
        
        binding.switchShowOnlineFirst.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.showOnlineFirst = isChecked
        }

        binding.switchSendByEnter.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.sendByEnter = isChecked
        }

        binding.switchLinkPreview.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.showLinkPreview = isChecked
        }
        
        binding.switchDisableQueue.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.disableMessageQueue = isChecked
        }

        binding.themeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val index = when (checkedId) {
                R.id.radioThemeStandard -> 0
                R.id.radioThemeTurquoise -> 1
                else -> 0
            }
            if (securePrefs.themePreset != index) {
                securePrefs.themePreset = index
                updateThemePreview()
            }
        }

        binding.btnSelectBackground.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            pickImageLauncher.launch(intent)
        }

        binding.btnRemoveBackground.setOnClickListener {
            securePrefs.chatBackgroundPath = null
            binding.ivBackgroundPreview.visibility = View.GONE
            binding.btnRemoveBackground.visibility = View.GONE
            updateThemePreview()
            Toast.makeText(requireContext(), R.string.background_removed, Toast.LENGTH_SHORT).show()
        }

        binding.switchPrivacyOnlyList.setOnCheckedChangeListener { _, isChecked ->
            if (securePrefs.privacyAllowOnlyFromList != isChecked) {
                securePrefs.privacyAllowOnlyFromList = isChecked
                repository.setPrivacyMode(isChecked)
            }
        }

        binding.switchNotifyMessages.setOnCheckedChangeListener { _, isChecked -> securePrefs.notifyMessages = isChecked }
        binding.switchNotifyLogin.setOnCheckedChangeListener { _, isChecked -> securePrefs.notifyLogin = isChecked }
        binding.switchNotifyNudge.setOnCheckedChangeListener { _, isChecked -> securePrefs.notifyNudge = isChecked }
        binding.switchNotifyAddedBy.setOnCheckedChangeListener { _, isChecked -> securePrefs.notifyAddedBy = isChecked }
        binding.switchVibration.setOnCheckedChangeListener { _, isChecked -> securePrefs.vibrationEnabled = isChecked }
        binding.switchChatSounds.setOnCheckedChangeListener { _, isChecked -> securePrefs.chatSoundsEnabled = isChecked }
        
        binding.switchPhoneStatus.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.phoneStatusEnabled = isChecked
        }

        binding.btnClearCache.setOnClickListener { clearCache() }
        binding.btnClearHistory.setOnClickListener { clearHistory() }
    }

    fun updateMode(mode: String) {
        binding.cardTheme.visibility = if (mode == "theme") View.VISIBLE else View.GONE
        binding.cardNotifications.visibility = if (mode == "notifications") View.VISIBLE else View.GONE
        binding.cardPrivacy.visibility = if (mode == "privacy") View.VISIBLE else View.GONE
        binding.cardStorage.visibility = if (mode == "storage") View.VISIBLE else View.GONE
        
        val titleRes = when(mode) {
            "theme" -> R.string.chat_settings
            "notifications" -> R.string.notifications
            "privacy" -> R.string.privacy
            "storage" -> R.string.storage
            else -> R.string.app_settings
        }
        binding.toolbar.setTitle(titleRes)
    }

    private fun loadSettings() {
        binding.switchSystemTheme.isChecked = securePrefs.followSystemTheme
        binding.switchDarkTheme.isChecked = securePrefs.darkTheme
        binding.switchDarkTheme.isEnabled = !securePrefs.followSystemTheme
        
        // Material You поддерживается только с Android 12 (API 31, S)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.switchMaterialYou.visibility = View.VISIBLE
            binding.switchMaterialYou.isChecked = securePrefs.useMaterialYou
            updateMaterialYouVisibility(securePrefs.useMaterialYou)
        } else {
            binding.switchMaterialYou.visibility = View.GONE
            // Если устройство не поддерживает, форсируем отображение пресетов тем
            updateMaterialYouVisibility(false)
        }

        binding.switchOpenChatsByDefault.isChecked = securePrefs.openChatsByDefault
        binding.switchShowOnlineFirst.isChecked = securePrefs.showOnlineFirst
        
        binding.switchSendByEnter.isChecked = securePrefs.sendByEnter
        binding.switchLinkPreview.isChecked = securePrefs.showLinkPreview
        binding.switchDisableQueue.isChecked = securePrefs.disableMessageQueue
        
        val themeId = when (securePrefs.themePreset) {
            0 -> R.id.radioThemeStandard
            1 -> R.id.radioThemeTurquoise
            else -> R.id.radioThemeStandard
        }
        binding.themeRadioGroup.check(themeId)
        updateThemePreview()

        val bgPath = securePrefs.chatBackgroundPath
        if (!bgPath.isNullOrEmpty()) {
            binding.ivBackgroundPreview.visibility = View.VISIBLE
            binding.btnRemoveBackground.visibility = View.VISIBLE
            binding.ivBackgroundPreview.load(Uri.parse(bgPath))
        }

        binding.switchPrivacyOnlyList.isChecked = securePrefs.privacyAllowOnlyFromList
        
        binding.switchNotifyMessages.isChecked = securePrefs.notifyMessages
        binding.switchNotifyLogin.isChecked = securePrefs.notifyLogin
        binding.switchNotifyNudge.isChecked = securePrefs.notifyNudge
        binding.switchNotifyAddedBy.isChecked = securePrefs.notifyAddedBy
        binding.switchVibration.isChecked = securePrefs.vibrationEnabled
        binding.switchChatSounds.isChecked = securePrefs.chatSoundsEnabled
        binding.switchPhoneStatus.isChecked = securePrefs.phoneStatusEnabled

        updateStorageInfo()
    }

    private fun updateMaterialYouVisibility(enabled: Boolean) {
        val visibility = if (enabled) View.GONE else View.VISIBLE
        binding.tvThemePresetsLabel.visibility = visibility
        binding.scrollThemes.visibility = visibility
    }

    private fun updateTheme() {
        val mode = when {
            securePrefs.followSystemTheme -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            securePrefs.darkTheme -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_NO
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    private fun updateThemePreview() {
        BubbleStyleHelper.applyStyle(
            binding.previewIncoming,
            binding.tvPreviewIncoming,
            null,
            true,
            securePrefs
        )
        BubbleStyleHelper.applyStyle(
            binding.previewOutgoing,
            binding.tvPreviewOutgoing,
            null,
            false,
            securePrefs
        )
        
        val bgPath = securePrefs.chatBackgroundPath
        if (!bgPath.isNullOrEmpty()) {
            binding.ivThemePreviewBg.load(Uri.parse(bgPath))
            binding.themePreview.background = null
        } else {
            binding.ivThemePreviewBg.setImageDrawable(null)
            binding.themePreview.setBackgroundColor(0x15000000)
        }
    }

    private fun saveChatBackground(uri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            securePrefs.chatBackgroundPath = uri.toString()
            binding.ivBackgroundPreview.visibility = View.VISIBLE
            binding.btnRemoveBackground.visibility = View.VISIBLE
            binding.ivBackgroundPreview.load(uri)
            updateThemePreview()
            Toast.makeText(requireContext(), R.string.background_updated, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "Error saving background", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStorageInfo() {
        lifecycleScope.launch {
            val cacheSize = viewModel.getCacheSize(requireContext())
            val historySize = viewModel.getHistorySize()
            
            binding.tvCacheLabel.text = getString(R.string.cache_label_size, formatSize(cacheSize))
            binding.tvMessagesLabel.text = getString(R.string.messages_label_size, formatSize(historySize))
            
            val total = cacheSize + historySize
            binding.tvTotalSpace.text = getString(R.string.total_storage_usage, formatSize(total))
            
            binding.progressCache.progress = if (total > 0L) (cacheSize * 100 / total).toInt() else 0
            binding.progressMessages.progress = if (total > 0L) (historySize * 100 / total).toInt() else 0
        }
    }

    private fun formatSize(size: Long): String {
        val kb = size / 1024
        val mb = kb / 1024
        return if (mb > 0) "$mb MB" else "$kb KB"
    }

    private fun clearCache() {
        lifecycleScope.launch {
            viewModel.clearCache(requireContext())
            updateStorageInfo()
            Toast.makeText(requireContext(), R.string.cache_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearHistory() {
        lifecycleScope.launch {
            viewModel.clearHistory()
            updateStorageInfo()
            Toast.makeText(requireContext(), R.string.clear_history, Toast.LENGTH_SHORT).show()
        }
    }

    private fun performBack() {
        val mainFragment = findMainFragment()
        if (mainFragment != null) {
            mainFragment.closeDetail()
        } else if (!findNavController().popBackStack()) {
            requireActivity().finish()
        }
    }

    private fun findMainFragment(): MainFragment? {
        var parent = parentFragment
        while (parent != null) {
            if (parent is MainFragment) return parent
            parent = parent.parentFragment
        }
        return null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
