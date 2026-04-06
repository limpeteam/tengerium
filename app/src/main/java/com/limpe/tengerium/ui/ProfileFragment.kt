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
import androidx.core.widget.NestedScrollView
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
import com.limpe.tengerium.util.FormattingUtils
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
            _binding?.profileScrollView?.post {
                _binding?.profileScrollView?.scrollTo(0, mainViewModel.profileScrollY)
            }
            _binding?.profileScrollView?.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                mainViewModel.profileScrollY = scrollY
            })
            updateAppInfo()
        }
    }

    private fun updateAppInfo() {
        val context = context ?: return
        val appName = getString(R.string.app_name)
        val codeName = AppConfig.getCodeName(context)
        val branch = AppConfig.getBranch(context)
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) { "0.0.1" }
        
        _binding?.tvAppInfo?.text = getString(R.string.app_info_full_format, appName, codeName, branch, versionName)
        _binding?.tvAppInfo?.setOnClickListener {
            val mainFragment = findMainFragment()
            if (mainFragment != null) {
                mainFragment.showDetail(AboutFragment(), addToBackStack = true)
            } else {
                findNavController().navigate(R.id.AboutFragment)
            }
        }
    }

    private fun setupUI() {
        val currentAccount = repository.getCurrentAccount()
        val isOwnProfile = profileAccount == null || profileAccount.equals(currentAccount, ignoreCase = true)
        val isModal = showsDialog && arguments?.getBoolean("as_modal") == true
        
        if (isOwnProfile) {
            _binding?.avatarStatusView?.statusView?.visibility = View.GONE
            _binding?.avatarStatusView?.statusBorderView?.visibility = View.GONE
        }

        _binding?.swipeBackLayout?.setOnSwipeBackListener {
            performCloseProfile()
        }

        _binding?.btnProfileBack?.visibility = if (isModal || profileAccount != null) View.VISIBLE else View.GONE
        _binding?.btnProfileBack?.setOnClickListener { 
            performCloseProfile()
        }

        if (isModal) {
            _binding?.root?.clipToOutline = true
            _binding?.root?.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_modal_profile)
        }

        _binding?.layoutSelfSettings?.visibility = if (isOwnProfile) View.VISIBLE else View.GONE
        _binding?.btnEditAvatar?.visibility = if (isOwnProfile) View.VISIBLE else View.GONE
        _binding?.btnEditProfile?.visibility = if (isOwnProfile) View.VISIBLE else View.GONE
        
        _binding?.btnEditAvatar?.setOnClickListener { 
            if (AppConfig.enableAvatarLibrary) {
                val mf = findMainFragment()
                if (mf != null) {
                    mf.showDetail(AvatarLibraryFragment(), addToBackStack = true)
                } else {
                    findNavController().navigate(R.id.AvatarLibraryFragment)
                }
            } else {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
                avatarPickerLauncher.launch(intent) 
            }
        }
        _binding?.btnEditProfile?.setOnClickListener { showEditProfileDialog() }
        _binding?.btnLogout?.setOnClickListener { logout() }
        
        _binding?.avatarStatusView?.setOnClickListener { showFullAvatar() }
        _binding?.btnFullAvatarBack?.setOnClickListener { hideFullAvatar() }

        _binding?.btnFullAvatarMenu?.setOnClickListener { view ->
            val popup = PopupMenu(requireContext(), view)
            popup.menu.add(getString(R.string.download))
            popup.setOnMenuItemClickListener {
                downloadAvatar()
                true
            }
            popup.show()
        }

        _binding?.btnTheme?.setOnClickListener { navigateToSettings("theme") }
        _binding?.btnNotifications?.setOnClickListener { navigateToSettings("notifications") }
        _binding?.btnPrivacy?.setOnClickListener { navigateToSettings("privacy") }
        _binding?.btnStorage?.setOnClickListener { navigateToSettings("storage") }

        _binding?.btnLanguage?.setOnClickListener { showLanguageDialog() }
        _binding?.btnStatusSelector?.setOnClickListener { showStatusDialog() }
    }

    private fun performCloseProfile() {
        val isModal = showsDialog && arguments?.getBoolean("as_modal") == true
        if (isModal) {
            dismiss()
        } else {
            val parent = findMainFragment()
            if (parent != null) {
                parent.closeDetail()
            } else {
                findNavController().popBackStack()
            }
        }
    }

    private fun navigateToSettings(mode: String) {
        val isModal = showsDialog && arguments?.getBoolean("as_modal") == true
        val mainFragment = findMainFragment()

        if (mainFragment != null) {
            val existing = mainFragment.childFragmentManager.findFragmentById(R.id.detail_container)
            if (existing is ChatSettingsFragment) {
                existing.updateMode(mode)
            } else {
                val fragment = ChatSettingsFragment().apply {
                    arguments = bundleOf("mode" to mode)
                }
                mainFragment.showDetail(fragment, addToBackStack = true)
            }
        } else {
            findNavController().navigate(R.id.ChatSettingsFragment, bundleOf("mode" to mode))
        }
        
        if (isModal) dismiss()
    }

    private val avatarPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data
            uri?.let { updateAvatar(it) }
        }
    }

    private fun showFullAvatar() {
        val b = _binding ?: return
        if (currentAvatarPath != null) {
            b.layoutFullAvatar.visibility = View.VISIBLE
            AvatarUtils.loadAvatar(b.ivFullAvatar, currentAvatarPath, profileAccount ?: "")
            b.layoutFullAvatar.animate().alpha(1f).setDuration(300).setListener(null)
            b.ivFullAvatar.scaleX = 0.5f
            b.ivFullAvatar.scaleY = 0.5f
            b.ivFullAvatar.animate().scaleX(1f).scaleY(1f).setDuration(300).start()
        }
    }
    
    private fun hideFullAvatar() {
        _binding?.layoutFullAvatar?.animate()?.alpha(0f)?.setDuration(300)?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    _binding?.layoutFullAvatar?.visibility = View.GONE
                }
            })
        _binding?.ivFullAvatar?.animate()?.scaleX(0.5f)?.scaleY(0.5f)?.setDuration(300)?.start()
    }

    private fun downloadAvatar() {
        val path = currentAvatarPath ?: return
        val account = profileAccount ?: "avatar"
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = BitmapFactory.decodeFile(path) ?: return@launch
                val appName = getString(R.string.app_name)
                val fileName = "${account}_${System.currentTimeMillis()}.jpg"
                val context = context ?: return@launch
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + appName)
                    }
                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let { context.contentResolver.openOutputStream(it)?.use { stream ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                        }
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val appDir = File(downloadsDir, appName)
                    if (!appDir.exists()) appDir.mkdirs()
                    val file = File(appDir, fileName)
                    FileOutputStream(file).use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream) }
                }
                withContext(Dispatchers.Main) { 
                    if (isAdded) Toast.makeText(requireContext(), R.string.saved_to_downloads, Toast.LENGTH_SHORT).show() 
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    if (isAdded) Toast.makeText(requireContext(), getString(R.string.save_error, e.message), Toast.LENGTH_SHORT).show() 
                }
            }
        }
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
                        val b = _binding ?: return@collectLatest
                        
                        if (isOwnProfile) {
                            val state = repository.loginState.value
                            val myNickname = if (state is MSNPLoginState.Success && state.account.lowercase() == targetAccount) {
                                state.nickname.ifEmpty { securePrefs.getNickname(targetAccount)?.ifEmpty { contact?.nickname } ?: contact?.nickname }
                            } else {
                                securePrefs.getNickname(targetAccount)?.ifEmpty { contact?.nickname } ?: contact?.nickname
                            }
                            b.tvNickname.text = FormattingUtils.formatBBCode(myNickname ?: targetAccount)
                            b.tvAccount.text = targetAccount
                            b.tvAccount.visibility = View.VISIBLE
                        } else {
                            b.tvNickname.text = FormattingUtils.formatBBCode(contact?.nickname ?: targetAccount)
                            b.tvPersonalMessage.text = FormattingUtils.formatBBCode(contact?.personalMessage ?: "")
                            b.tvAccount.visibility = View.GONE
                            currentAvatarPath = contact?.avatarUrl
                            b.avatarStatusView.setAvatar(currentAvatarPath, targetAccount)
                            b.layoutProfileDetails.visibility = View.VISIBLE
                            b.tvDetailEmail.text = targetAccount
                            updateDetailStatusUI(contact?.status ?: MSNPProto.Status.OFFLINE)
                        }
                        if (!isOwnProfile) updateStatusUI(contact?.status ?: MSNPProto.Status.OFFLINE)
                    }
                }

                if (isOwnProfile) {
                    launch {
                        repository.loginState.collectLatest { state ->
                            val b = _binding ?: return@collectLatest
                            if (state is MSNPLoginState.Success && targetAccount.equals(state.account, ignoreCase = true)) {
                                    val name = state.nickname.ifEmpty { securePrefs.getNickname(state.account)?.ifEmpty { state.account } ?: state.account }
                                    b.tvNickname.text = FormattingUtils.formatBBCode(name ?: targetAccount)
                                    currentAvatarPath = state.avatarUrl
                                    b.avatarStatusView.setAvatar(currentAvatarPath, state.account)
                            }
                        }
                    }
                    launch { repository.myStatus.collectLatest { updateStatusUI(it) } }
                    launch { repository.myPersonalMessage.collectLatest { _binding?.tvPersonalMessage?.text = FormattingUtils.formatBBCode(it) } }
                }
            }
        }
    }

    private fun updateStatusUI(status: String) {
        val b = _binding ?: return
        val (textRes, colorRes) = getStatusResources(status)
        b.btnStatusSelector.setText(textRes)
        b.btnStatusSelector.setIconTintResource(colorRes)
        b.avatarStatusView.setStatus(status)
    }
    
    private fun updateDetailStatusUI(status: String) {
        val b = _binding ?: return
        val (textRes, colorRes) = getStatusResources(status)
        b.tvDetailStatus.setText(textRes)
        b.ivDetailStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
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
        AlertDialog.Builder(requireContext()).setTitle(R.string.status_settings).setItems(statuses.map { it.first }.toTypedArray()) { _, which ->
                repository.setStatus(statuses[which].second)
            }.show()
    }

    private fun showLanguageDialog() {
        val languages = arrayOf(getString(R.string.system_default) to "system", "English" to "en", "Русский" to "ru", "Беларуская" to "be", "Polski" to "pl", "Français" to "fr", "Қазақша" to "kk", "Українська" to "uk", "中文" to "zh")
        val currentLang = securePrefs.language
        val checkedItem = languages.indexOfFirst { it.second == currentLang }.coerceAtLeast(0)
        AlertDialog.Builder(requireContext()).setTitle(R.string.language_settings).setSingleChoiceItems(languages.map { it.first }.toTypedArray(), checkedItem) { dialog, which ->
                val selectedLang = languages[which].second
                if (selectedLang != null && selectedLang != currentLang) {
                    securePrefs.language = selectedLang
                    TengeriumApp.applyLanguage(requireContext(), selectedLang)
                    restartActivity()
                }
                dialog.dismiss()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun restartActivity() {
        val intent = requireActivity().intent
        requireActivity().finish()
        startActivity(intent)
    }

    private fun showEditProfileDialog() {
        val context = requireContext()
        val b = _binding ?: return
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val etNickname = EditText(context).apply { hint = getString(R.string.nickname_hint); setText(b.tvNickname.text) }
        val etPersonalMessage = EditText(context).apply { hint = getString(R.string.personal_message_hint); setText(b.tvPersonalMessage.text) }
        layout.addView(etNickname); layout.addView(etPersonalMessage)
        AlertDialog.Builder(context).setTitle(R.string.edit_profile).setView(layout).setPositiveButton(R.string.update) { _, _ ->
                val newNick = etNickname.text.toString().trim()
                if (newNick.isNotEmpty() && newNick != b.tvNickname.text.toString()) repository.updateNickname(newNick)
                repository.updatePersonalMessage(etPersonalMessage.text.toString().trim())
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun updateAvatar(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                repository.updateAvatar(uri.toString())
                if (isAdded) Toast.makeText(requireContext(), R.string.avatar_updated, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                if (isAdded) Toast.makeText(requireContext(), getString(R.string.login_error, e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun logout() {
        repository.logout()
        findNavController().navigate(R.id.AuthFragment)
        if (showsDialog) dismiss()
    }

    private fun findMainFragment(): MainFragment? {
        return generateSequence(parentFragment) { it.parentFragment }.filterIsInstance<MainFragment>().firstOrNull()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
