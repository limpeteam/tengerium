package com.limpe.tengerium.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.AppConfig
import com.limpe.tengerium.data.MSNPLoginState
import com.limpe.tengerium.data.MSNPRepository
import com.limpe.tengerium.data.protocol.MSNPProto
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.databinding.FragmentProfileBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ProfileFragment : DialogFragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: MSNPRepository
    private lateinit var securePrefs: SecurePrefs
    private var profileAccount: String? = null
    private var currentAvatarPath: String? = null
    private val mainViewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory((requireActivity().application as TengeriumApp).repository)
    }

    private val avatarPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data
            uri?.let { updateAvatar(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isModal = arguments?.getBoolean("as_modal") == true
        showsDialog = isModal
        
        if (isModal) {
            setStyle(STYLE_NO_TITLE, R.style.Theme_Tengerium_ModalProfile)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        if (showsDialog && arguments?.getBoolean("as_modal") == true) {
            dialog?.window?.apply {
                setLayout(
                    (resources.displayMetrics.widthPixels * 0.8).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
                
                attributes = attributes.apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 200
                    windowAnimations = android.R.style.Animation_Dialog
                }
                
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = (requireActivity().application as TengeriumApp).repository
        securePrefs = SecurePrefs(requireContext())
        profileAccount = arguments?.getString("account")

        setupUI()
        observeData()

        if (profileAccount == null) {
            binding.swipeBackLayout.post {
                val scrollView = binding.swipeBackLayout.getChildAt(0) as? androidx.core.widget.NestedScrollView
                scrollView?.scrollTo(0, mainViewModel.profileScrollY)
            }
            
            val scrollView = binding.swipeBackLayout.getChildAt(0) as? androidx.core.widget.NestedScrollView
            scrollView?.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                mainViewModel.profileScrollY = scrollY
            })
            
            updateAppInfo()
        }
    }

    private fun updateAppInfo() {
        val context = requireContext()
        val appName = getString(R.string.app_name)
        val codeName = AppConfig.getCodeName(context)
        val branch = AppConfig.getBranch(context)
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "0.0.1"
        }
        
        binding.tvAppInfo.text = getString(R.string.app_info_full_format, appName, codeName, branch, versionName)
        binding.tvAppInfo.setOnClickListener {
            val mainFragment = findMainFragment()
            if (mainFragment != null && mainFragment.isTablet) {
                mainFragment.showDetail(AboutFragment())
            } else {
                findNavController().navigate(R.id.AboutFragment)
            }
        }
    }

    private fun setupUI() {
        val currentAccount = repository.getCurrentAccount()
        val isOwnProfile = profileAccount == null || profileAccount.equals(currentAccount, ignoreCase = true)
        val isModal = showsDialog && arguments?.getBoolean("as_modal") == true
        
        binding.btnProfileBack.visibility = if (isModal || profileAccount != null) View.VISIBLE else View.GONE
        binding.btnProfileBack.setOnClickListener { 
            if (isModal) dismiss() else {
                val parent = parentFragment
                if (parent is MainFragment && parent.isTablet) {
                    parent.childFragmentManager.popBackStack()
                } else {
                    findNavController().popBackStack()
                }
            }
        }
        
        if (isModal) {
            binding.root.clipToOutline = true
            binding.root.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_modal_profile)
        }

        binding.layoutSelfSettings.visibility = if (isOwnProfile) View.VISIBLE else View.GONE
        binding.btnEditAvatar.visibility = if (isOwnProfile) View.VISIBLE else View.GONE
        binding.btnEditProfile.visibility = if (isOwnProfile) View.VISIBLE else View.GONE
        
        binding.btnEditAvatar.setOnClickListener { 
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            avatarPickerLauncher.launch(intent) 
        }
        binding.btnEditProfile.setOnClickListener { showEditProfileDialog() }
        binding.btnLogout.setOnClickListener { logout() }
        
        binding.ivAvatar.setOnClickListener {
            showFullAvatar()
        }

        binding.btnFullAvatarBack.setOnClickListener {
            hideFullAvatar()
        }

        binding.btnFullAvatarMenu.setOnClickListener { view ->
            val popup = PopupMenu(requireContext(), view)
            popup.menu.add(getString(R.string.download))
            popup.setOnMenuItemClickListener {
                downloadAvatar()
                true
            }
            popup.show()
        }

        binding.btnTheme.setOnClickListener { navigateToSettings("theme") }
        binding.btnNotifications.setOnClickListener { navigateToSettings("notifications") }
        binding.btnPrivacy.setOnClickListener { navigateToSettings("privacy") }
        binding.btnStorage.setOnClickListener { navigateToSettings("storage") }

        binding.btnLanguage.setOnClickListener {
            showLanguageDialog()
        }

        binding.btnStatusSelector.setOnClickListener {
            showStatusDialog()
        }
    }

    private fun showFullAvatar() {
        if (currentAvatarPath != null) {
            binding.layoutFullAvatar.visibility = View.VISIBLE
            AvatarUtils.loadAvatar(binding.ivFullAvatar, currentAvatarPath, profileAccount ?: "")
            
            // Animation
            binding.layoutFullAvatar.animate()
                .alpha(1f)
                .setDuration(300)
                .setListener(null)
            
            binding.ivFullAvatar.scaleX = 0.5f
            binding.ivFullAvatar.scaleY = 0.5f
            binding.ivFullAvatar.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .start()
        }
    }
    
    private fun hideFullAvatar() {
        binding.layoutFullAvatar.animate()
            .alpha(0f)
            .setDuration(300)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.layoutFullAvatar.visibility = View.GONE
                }
            })
            
        binding.ivFullAvatar.animate()
            .scaleX(0.5f)
            .scaleY(0.5f)
            .setDuration(300)
            .start()
    }

    private fun downloadAvatar() {
        val path = currentAvatarPath ?: return
        val account = profileAccount ?: "avatar"
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap == null) return@launch
                
                val appName = getString(R.string.app_name)
                val fileName = "${account}_${System.currentTimeMillis()}.jpg"
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + appName)
                    }
                    
                    val uri = requireContext().contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let {
                        requireContext().contentResolver.openOutputStream(it)?.use { stream ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                        }
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val appDir = File(downloadsDir, appName)
                    if (!appDir.exists()) appDir.mkdirs()
                    
                    val file = File(appDir, fileName)
                    FileOutputStream(file).use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), R.string.saved_to_downloads, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.save_error, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun navigateToSettings(mode: String) {
        val isModal = showsDialog && arguments?.getBoolean("as_modal") == true
        val mainFragment = findMainFragment()

        if (mainFragment != null && mainFragment.isTablet) {
            val existing = mainFragment.childFragmentManager.findFragmentById(R.id.chat_nav_container)
            if (existing is ChatSettingsFragment) {
                existing.updateMode(mode)
            } else {
                val fragment = ChatSettingsFragment().apply {
                    arguments = bundleOf("mode" to mode)
                }
                mainFragment.showDetail(fragment)
            }
        } else {
            findNavController().navigate(R.id.ChatSettingsFragment, bundleOf("mode" to mode))
        }
        
        if (isModal) dismiss()
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val currentAccount = repository.getCurrentAccount()
                val targetAccount = (profileAccount ?: currentAccount ?: "").lowercase()
                val isOwnProfile = profileAccount == null || targetAccount.equals(currentAccount?.lowercase(), ignoreCase = true)
                
                launch {
                    repository.contacts.collectLatest { contacts ->
                        val contact = contacts.find { it.account.lowercase().equals(targetAccount, ignoreCase = true) }
                        
                        if (isOwnProfile) {
                            // Для своего профиля сначала пытаемся взять ник из LoginState, потом из настроек, потом из списка контактов
                            val state = repository.loginState.value
                            val myNickname = if (state is MSNPLoginState.Success && state.account.lowercase() == targetAccount) {
                                state.nickname.ifEmpty { securePrefs.getNickname(targetAccount)?.ifEmpty { contact?.nickname } ?: contact?.nickname }
                            } else {
                                securePrefs.getNickname(targetAccount)?.ifEmpty { contact?.nickname } ?: contact?.nickname
                            }
                            
                            binding.tvNickname.text = FormattingUtils.formatBBCode(myNickname ?: targetAccount)
                            binding.tvAccount.text = targetAccount
                        } else {
                            binding.tvNickname.text = FormattingUtils.formatBBCode(contact?.nickname ?: targetAccount)
                            binding.tvPersonalMessage.text = FormattingUtils.formatBBCode(contact?.personalMessage ?: "")
                            binding.tvAccount.text = targetAccount
                            currentAvatarPath = contact?.avatarUrl
                            AvatarUtils.loadAvatar(binding.ivAvatar, currentAvatarPath, targetAccount)
                            
                            binding.layoutProfileDetails.visibility = View.VISIBLE
                            binding.tvDetailEmail.text = targetAccount
                            updateDetailStatusUI(contact?.status ?: MSNPProto.Status.OFFLINE)
                        }
                        
                        if (!isOwnProfile) {
                            updateStatusUI(contact?.status ?: MSNPProto.Status.OFFLINE)
                        }
                    }
                }

                if (isOwnProfile) {
                    launch {
                        repository.loginState.collectLatest { state ->
                            if (state is MSNPLoginState.Success) {
                                if (targetAccount.equals(state.account, ignoreCase = true)) {
                                    val name = state.nickname.ifEmpty { securePrefs.getNickname(state.account)?.ifEmpty { state.account } ?: state.account }
                                    binding.tvNickname.text = FormattingUtils.formatBBCode(name ?: targetAccount)
                                    currentAvatarPath = state.avatarUrl
                                    AvatarUtils.loadAvatar(binding.ivAvatar, currentAvatarPath, state.account)
                                }
                            }
                        }
                    }
                    launch {
                        repository.myStatus.collectLatest { status ->
                            updateStatusUI(status)
                        }
                    }
                    launch {
                        repository.myPersonalMessage.collectLatest { psm ->
                            binding.tvPersonalMessage.text = FormattingUtils.formatBBCode(psm)
                        }
                    }
                }
            }
        }
    }

    private fun updateStatusUI(status: String) {
        val (textRes, colorRes) = getStatusResources(status)
        
        binding.btnStatusSelector.setText(textRes)
        binding.btnStatusSelector.setIconTintResource(colorRes)
        binding.viewStatus.setBackgroundResource(R.drawable.status_dot)
        binding.viewStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(requireContext(), colorRes)
        )
    }
    
    private fun updateDetailStatusUI(status: String) {
        val (textRes, colorRes) = getStatusResources(status)
        binding.tvDetailStatus.setText(textRes)
        binding.ivDetailStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(requireContext(), colorRes)
        )
    }
    
    private fun getStatusResources(status: String): Pair<Int, Int> {
        return when (status) {
            MSNPProto.Status.ONLINE -> R.string.online to R.color.status_online
            MSNPProto.Status.BUSY -> R.string.busy to R.color.status_busy
            MSNPProto.Status.AWAY -> R.string.away to R.color.status_away
            MSNPProto.Status.BRB -> R.string.brb to R.color.status_away
            MSNPProto.Status.PHONE -> R.string.on_phone to R.color.status_busy
            MSNPProto.Status.LUNCH -> R.string.out_to_lunch to R.color.status_away
            MSNPProto.Status.HIDDEN, MSNPProto.Status.OFFLINE -> R.string.offline to R.color.status_offline
            else -> R.string.offline to R.color.status_offline
        }
    }

    private fun showStatusDialog() {
        val statuses = arrayOf(
            getString(R.string.online) to MSNPProto.Status.ONLINE,
            getString(R.string.busy) to MSNPProto.Status.BUSY,
            getString(R.string.away) to MSNPProto.Status.AWAY,
            getString(R.string.brb) to MSNPProto.Status.BRB,
            getString(R.string.on_phone) to MSNPProto.Status.PHONE,
            getString(R.string.out_to_lunch) to MSNPProto.Status.LUNCH,
            getString(R.string.offline) to MSNPProto.Status.HIDDEN
        )

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.status_settings)
            .setItems(statuses.map { it.first }.toTypedArray()) { _, which ->
                repository.setStatus(statuses[which].second)
            }
            .show()
    }

    private fun showLanguageDialog() {
        val languages = arrayOf(
            getString(R.string.system_default) to "system",
            "English" to "en",
            "Русский" to "ru",
            "Беларуская" to "be",
            "Polski" to "pl",
            "Français" to "fr",
            "Қазақша" to "kk",
            "Українська" to "uk",
            "中文" to "zh"
        )
        
        val currentLang = securePrefs.language
        val checkedItem = languages.indexOfFirst { it.second == currentLang }.coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.language_settings)
            .setSingleChoiceItems(languages.map { it.first }.toTypedArray(), checkedItem) { dialog, which ->
                val selectedLang = languages[which].second
                if (selectedLang != null && selectedLang != currentLang) {
                    securePrefs.language = selectedLang
                    TengeriumApp.applyLanguage(requireContext(), selectedLang)
                    restartActivity()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun restartActivity() {
        val intent = requireActivity().intent
        requireActivity().finish()
        startActivity(intent)
    }

    private fun showEditProfileDialog() {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }

        val etNickname = EditText(context).apply {
            hint = getString(R.string.nickname_hint)
            setText(binding.tvNickname.text)
        }
        
        val etPersonalMessage = EditText(context).apply {
            hint = getString(R.string.personal_message_hint)
            setText(binding.tvPersonalMessage.text)
        }

        layout.addView(etNickname)
        layout.addView(etPersonalMessage)

        AlertDialog.Builder(context)
            .setTitle(R.string.edit_profile)
            .setView(layout)
            .setPositiveButton(R.string.update) { _, _ ->
                val newNick = etNickname.text.toString().trim()
                val newPsm = etPersonalMessage.text.toString().trim()
                
                if (newNick.isNotEmpty() && newNick != binding.tvNickname.text.toString()) {
                    repository.updateNickname(newNick)
                }
                repository.updatePersonalMessage(newPsm)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateAvatar(uri: Uri) {
        lifecycleScope.launch {
            try {
                repository.updateAvatar(uri.toString())
                Toast.makeText(requireContext(), R.string.avatar_updated, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.login_error, e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun logout() {
        repository.logout()
        findNavController().navigate(R.id.AuthFragment)
        if (showsDialog) dismiss()
    }

    private fun findMainFragment(): MainFragment? {
        return generateSequence(parentFragment) { it.parentFragment }
            .filterIsInstance<MainFragment>()
            .firstOrNull()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
