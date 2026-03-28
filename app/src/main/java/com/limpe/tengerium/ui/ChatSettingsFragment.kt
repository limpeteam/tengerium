package com.limpe.tengerium.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import coil.load
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.AppConfig
import com.limpe.tengerium.data.ThemePreset
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.databinding.FragmentChatSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class ChatSettingsFragment : Fragment() {

    private var _binding: FragmentChatSettingsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var securePrefs: SecurePrefs
    private var mode: String = "theme" // "theme", "privacy", "notifications" or "storage"

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            uri?.let {
                lifecycleScope.launch(Dispatchers.IO) {
                    val cachedPath = cacheBackground(it)
                    withContext(Dispatchers.Main) {
                        if (cachedPath != null) {
                            securePrefs.chatBackgroundPath = cachedPath
                            updateBackgroundPreview(cachedPath)
                            Toast.makeText(requireContext(), getString(R.string.background_updated), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), "Failed to save background", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun cacheBackground(uri: Uri): String? {
        return try {
            val context = requireContext()
            val backgroundsDir = File(context.filesDir, "backgrounds")
            if (!backgroundsDir.exists()) backgroundsDir.mkdirs()
            
            // Удаляем старый фон если есть
            backgroundsDir.listFiles()?.forEach { it.delete() }
            
            val fileName = "chat_bg_${System.currentTimeMillis()}.jpg"
            val destFile = File(backgroundsDir, fileName)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private val phonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            securePrefs.phoneStatusEnabled = true
            binding.switchPhoneStatus.isChecked = true
        } else {
            binding.switchPhoneStatus.isChecked = false
            Toast.makeText(requireContext(), R.string.oobe_phone_description, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        securePrefs = SecurePrefs(requireContext())
        mode = arguments?.getString("mode") ?: "theme"

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        setupInitialStates()
        setupListeners()
        applyMode()
        
        if (mode == "storage") {
            updateStorageStats()
        }
    }

    fun updateMode(newMode: String) {
        mode = newMode
        if (_binding != null) {
            applyMode()
            if (mode == "storage") {
                updateStorageStats()
            }
        }
    }

    private fun applyMode() {
        binding.cardTheme.visibility = View.GONE
        binding.cardPrivacy.visibility = View.GONE
        binding.cardNotifications.visibility = View.GONE
        binding.cardStorage.visibility = View.GONE

        when (mode) {
            "privacy" -> {
                binding.toolbar.title = getString(R.string.privacy)
                binding.cardPrivacy.visibility = View.VISIBLE
            }
            "notifications" -> {
                binding.toolbar.title = getString(R.string.notifications)
                binding.cardNotifications.visibility = View.VISIBLE
            }
            "storage" -> {
                binding.toolbar.title = getString(R.string.storage)
                binding.cardStorage.visibility = View.VISIBLE
            }
            else -> {
                binding.toolbar.title = getString(R.string.theme)
                binding.cardTheme.visibility = View.VISIBLE
            }
        }
    }

    private fun setupInitialStates() {
        val isDynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DynamicColors.isDynamicColorAvailable()
        
        if (!isDynamicAvailable) {
            binding.switchMaterialYou.isChecked = false
            binding.switchMaterialYou.isEnabled = false
            binding.switchMaterialYou.alpha = 0.5f
            securePrefs.useMaterialYou = false
        } else {
            binding.switchMaterialYou.isEnabled = true
            binding.switchMaterialYou.alpha = 1.0f
            binding.switchMaterialYou.isChecked = securePrefs.useMaterialYou
        }

        binding.switchDebugPopups.isChecked = securePrefs.debugEnabled
        
        // New Settings
        binding.switchSystemTheme.isChecked = securePrefs.followSystemTheme
        binding.switchDarkTheme.isChecked = securePrefs.darkTheme
        binding.switchDarkTheme.isEnabled = !securePrefs.followSystemTheme
        binding.switchDarkTheme.alpha = if (securePrefs.followSystemTheme) 0.5f else 1.0f

        binding.switchSendByEnter.isChecked = securePrefs.sendByEnter
        binding.switchLinkPreview.isChecked = securePrefs.showLinkPreview
        binding.switchOpenChatsByDefault.isChecked = securePrefs.openChatsByDefault
        binding.switchDisableQueue.isChecked = securePrefs.disableMessageQueue
        
        val currentPreset = securePrefs.themePreset
        when (currentPreset) {
            0 -> binding.radioTheme0.isChecked = true
            1 -> binding.radioTheme1.isChecked = true
            2 -> binding.radioTheme2.isChecked = true
            3 -> binding.radioTheme3.isChecked = true
        }
        
        updateThemeSelectionEnabled(!securePrefs.useMaterialYou)
        updateThemePreview(if (securePrefs.useMaterialYou) -1 else currentPreset)
        
        updateBackgroundPreview(securePrefs.chatBackgroundPath)

        // Privacy state
        binding.switchPrivacyOnlyList.isChecked = securePrefs.privacyAllowOnlyFromList

        // Notifications state
        binding.switchNotifyMessages.isChecked = securePrefs.notifyMessages
        binding.switchNotifyLogin.isChecked = securePrefs.notifyLogin
        binding.switchNotifyNudge.isChecked = securePrefs.notifyNudge
        binding.switchNotifyAddedBy.isChecked = securePrefs.notifyAddedBy
        binding.switchVibration.isChecked = securePrefs.vibrationEnabled

        // Phone status state
        val hasPhonePermission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        binding.switchPhoneStatus.isChecked = securePrefs.phoneStatusEnabled && hasPhonePermission
    }

    private fun updateThemeSelectionEnabled(enabled: Boolean) {
        binding.themeRadioGroup.isEnabled = enabled
        binding.radioTheme0.isEnabled = enabled
        binding.radioTheme1.isEnabled = enabled
        binding.radioTheme2.isEnabled = enabled
        binding.radioTheme3.isEnabled = enabled
        binding.scrollThemes.alpha = if (enabled) 1.0f else 0.5f
        binding.tvThemePresetsLabel.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun setupListeners() {
        binding.switchSystemTheme.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.followSystemTheme = isChecked
            binding.switchDarkTheme.isEnabled = !isChecked
            binding.switchDarkTheme.alpha = if (isChecked) 0.5f else 1.0f
            applyThemeChange()
        }

        binding.switchDarkTheme.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.darkTheme = isChecked
            applyThemeChange()
        }

        binding.switchMaterialYou.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.useMaterialYou = isChecked
            updateThemeSelectionEnabled(!isChecked)
            updateThemePreview(if (isChecked) -1 else securePrefs.themePreset)
            requireActivity().recreate()
        }

        binding.switchOpenChatsByDefault.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.openChatsByDefault = isChecked
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
            val presetIndex = when (checkedId) {
                R.id.radioTheme0 -> 0
                R.id.radioTheme1 -> 1
                R.id.radioTheme2 -> 2
                R.id.radioTheme3 -> 3
                else -> 0
            }
            securePrefs.themePreset = presetIndex
            updateThemePreview(presetIndex)
        }

        binding.btnSelectBackground.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            pickImageLauncher.launch(intent)
        }

        binding.btnRemoveBackground.setOnClickListener {
            securePrefs.chatBackgroundPath?.let { path ->
                try {
                    val file = File(path)
                    if (file.exists()) file.delete()
                } catch (e: Exception) {}
            }
            securePrefs.chatBackgroundPath = null
            updateBackgroundPreview(null)
            Toast.makeText(requireContext(), getString(R.string.background_removed), Toast.LENGTH_SHORT).show()
        }

        binding.switchDebugPopups.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.debugEnabled = isChecked
            AppConfig.showDebugToasts = isChecked
        }

        binding.switchPrivacyOnlyList.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.privacyAllowOnlyFromList = isChecked
            val app = requireActivity().application as TengeriumApp
            app.repository.setPrivacyMode(isChecked)
        }

        // Notification listeners
        binding.switchNotifyMessages.setOnCheckedChangeListener { _, isChecked -> securePrefs.notifyMessages = isChecked }
        binding.switchNotifyLogin.setOnCheckedChangeListener { _, isChecked -> securePrefs.notifyLogin = isChecked }
        binding.switchNotifyNudge.setOnCheckedChangeListener { _, isChecked -> securePrefs.notifyNudge = isChecked }
        binding.switchNotifyAddedBy.setOnCheckedChangeListener { _, isChecked -> securePrefs.notifyAddedBy = isChecked }
        binding.switchVibration.setOnCheckedChangeListener { _, isChecked -> securePrefs.vibrationEnabled = isChecked }

        binding.switchPhoneStatus.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                    phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                } else {
                    securePrefs.phoneStatusEnabled = true
                }
            } else {
                securePrefs.phoneStatusEnabled = false
            }
        }

        // Storage listeners
        binding.btnClearCache.setOnClickListener {
            clearCache()
        }

        binding.btnClearHistory.setOnClickListener {
            showClearHistoryDialog()
        }
    }

    private fun applyThemeChange() {
        if (securePrefs.followSystemTheme) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } else {
            if (securePrefs.darkTheme) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
    }

    private fun updateBackgroundPreview(path: String?) {
        if (!path.isNullOrEmpty()) {
            binding.ivBackgroundPreview.visibility = View.VISIBLE
            binding.btnRemoveBackground.visibility = View.VISIBLE
            val file = File(path)
            if (file.exists()) {
                binding.ivBackgroundPreview.load(file)
            } else {
                binding.ivBackgroundPreview.load(Uri.parse(path))
            }
        } else {
            binding.ivBackgroundPreview.visibility = View.GONE
            binding.btnRemoveBackground.visibility = View.GONE
        }
    }

    private fun updateThemePreview(index: Int) {
        val density = resources.displayMetrics.density

        if (index == -1) {
            val incomingColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSecondaryContainer)
            val outgoingColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer)
            val onIncoming = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSecondaryContainer)
            val onOutgoing = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer)

            binding.previewIncoming.setCardBackgroundColor(incomingColor)
            binding.tvPreviewIncoming.setTextColor(onIncoming)
            binding.previewOutgoing.setCardBackgroundColor(outgoingColor)
            binding.tvPreviewOutgoing.setTextColor(onOutgoing)
        } else {
            val preset = try { ThemePreset.presets[index] } catch(e: Exception) { ThemePreset.presets[0] }
            binding.previewIncoming.setCardBackgroundColor(preset.incomingBubbleColor)
            binding.tvPreviewIncoming.setTextColor(preset.onIncomingTextColor)
            binding.previewOutgoing.setCardBackgroundColor(preset.outgoingBubbleColor)
            binding.tvPreviewOutgoing.setTextColor(preset.onOutgoingTextColor)
        }

        val radius = 20f * density
        binding.previewIncoming.shapeAppearanceModel = binding.previewIncoming.shapeAppearanceModel.toBuilder()
            .setAllCornerSizes(radius)
            .build()
        binding.previewOutgoing.shapeAppearanceModel = binding.previewOutgoing.shapeAppearanceModel.toBuilder()
            .setAllCornerSizes(radius)
            .build()
    }

    private fun updateStorageStats() {
        lifecycleScope.launch(Dispatchers.IO) {
            val context = requireContext()
            val cacheSize = getDirSize(context.cacheDir)
            
            // Calculate DB size including all related files (-wal, -shm, journal, etc.)
            var dbSize = 0L
            val dbName = "tengerium_secure.db"
            val dbFile = context.getDatabasePath(dbName)
            val dbDir = dbFile.parentFile
            
            if (dbDir != null && dbDir.exists()) {
                dbDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith(dbName)) {
                        dbSize += file.length()
                    }
                }
            }
            
            // If still 0, try to find it in the standard 'databases' folder explicitly
            if (dbSize == 0L) {
                val altDbDir = File(context.applicationInfo.dataDir, "databases")
                if (altDbDir.exists()) {
                    altDbDir.listFiles()?.forEach { file ->
                        if (file.name.startsWith(dbName)) {
                            dbSize += file.length()
                        }
                    }
                }
            }
            
            val totalAppSize = cacheSize + dbSize
            
            val stat = StatFs(Environment.getDataDirectory().path)
            val totalDeviceSize = stat.blockCountLong * stat.blockSizeLong
            val percentOfDevice = (totalAppSize.toDouble() / totalDeviceSize.toDouble() * 100).coerceAtLeast(0.01)

            val totalSizeStr = Formatter.formatShortFileSize(context, totalAppSize)
            val cacheSizeStr = Formatter.formatShortFileSize(context, cacheSize)
            val dbSizeStr = Formatter.formatShortFileSize(context, dbSize)

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                binding.tvTotalSpace.text = getString(R.string.app_memory_percent, String.format(Locale.getDefault(), "%.2f", percentOfDevice), totalSizeStr)
                
                binding.tvCacheLabel.text = getString(R.string.cache_label, cacheSizeStr)
                binding.tvMessagesLabel.text = getString(R.string.messages_label, dbSizeStr)
                
                val max = totalAppSize.coerceAtLeast(1L)
                binding.progressCache.progress = ((cacheSize.toDouble() / max.toDouble()) * 100).toInt()
                binding.progressMessages.progress = ((dbSize.toDouble() / max.toDouble()) * 100).toInt()
            }
        }
    }

    private fun getDirSize(dir: File): Long {
        var size = 0L
        dir.listFiles()?.forEach {
            size += if (it.isDirectory) getDirSize(it) else it.length()
        }
        return size
    }

    private fun clearCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            val context = requireContext()
            context.cacheDir.deleteRecursively()
            context.cacheDir.mkdirs()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
                updateStorageStats()
            }
        }
    }

    private fun showClearHistoryDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_history_title)
            .setMessage(R.string.clear_history_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                clearHistory()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            val app = requireActivity().application as TengeriumApp
            app.repository.clearAllMessages()
            delay(1000)
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), R.string.history_cleared, Toast.LENGTH_SHORT).show()
                updateStorageStats()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
