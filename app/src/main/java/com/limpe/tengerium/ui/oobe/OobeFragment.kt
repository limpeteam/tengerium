package com.limpe.tengerium.ui.oobe

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.AppConfig
import com.limpe.tengerium.data.MSNPLoginState
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.databinding.FragmentAuthBinding
import com.limpe.tengerium.databinding.FragmentOobeBinding
import com.limpe.tengerium.databinding.FragmentOobeNicknameBinding
import com.limpe.tengerium.databinding.FragmentOobePermissionsBinding
import com.limpe.tengerium.databinding.FragmentOobeWelcomeBinding
import com.limpe.tengerium.ui.AuthViewModel
import com.limpe.tengerium.ui.AvatarUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class OobeFragment : Fragment() {

    private var _binding: FragmentOobeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AuthViewModel by activityViewModels {
        val app = requireActivity().application as TengeriumApp
        AuthViewModel.Factory(app.repository, app)
    }
    private lateinit var securePrefs: SecurePrefs

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        navigateToNextStep()
    }

    private val phonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        navigateToNextStep()
    }

    private enum class OobeStep {
        WELCOME, AUTH, NICKNAME, NOTIFICATIONS, PHONE
    }

    private val enabledSteps: List<OobeStep> by lazy {
        mutableListOf<OobeStep>().apply {
            if (AppConfig.oobeWelcomeEnabled) add(OobeStep.WELCOME)
            add(OobeStep.AUTH) // Окно логина обязательно
            if (AppConfig.oobeNicknameEnabled) add(OobeStep.NICKNAME)
            if (AppConfig.oobeNotificationsEnabled) add(OobeStep.NOTIFICATIONS)
            if (AppConfig.oobePhoneStatusEnabled) add(OobeStep.PHONE)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOobeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        securePrefs = SecurePrefs(requireContext())

        val account = securePrefs.getSavedAccount()
        if (account != null && securePrefs.isOobeDone(account)) {
            safeNavigate(R.id.action_OobeFragment_to_MainFragment)
            return
        }

        setupViewPager()
        observeLoginInOobe()
    }

    private fun setupViewPager() {
        val adapter = OobeAdapter()
        binding.oobeViewPager.adapter = adapter
        binding.oobeViewPager.isUserInputEnabled = false 
    }

    private fun navigateToNextStep() {
        val currentPos = binding.oobeViewPager.currentItem
        val nextPos = currentPos + 1
        
        if (nextPos < enabledSteps.size) {
            val nextStep = enabledSteps[nextPos]
            
            // Проверка необходимости разрешений
            if (nextStep == OobeStep.NOTIFICATIONS && !needsNotificationPermission()) {
                binding.oobeViewPager.currentItem = nextPos
                navigateToNextStep()
                return
            }
            if (nextStep == OobeStep.PHONE && !needsPhonePermission()) {
                binding.oobeViewPager.currentItem = nextPos
                navigateToNextStep()
                return
            }
            
            binding.oobeViewPager.currentItem = nextPos
        } else {
            completeOobe()
        }
    }

    private fun needsNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
    }

    private fun needsPhonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED
    }

    private fun hideKeyboard() {
        val view = activity?.currentFocus ?: view
        view?.let {
            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun completeOobe() {
        hideKeyboard()
        val account = securePrefs.getSavedAccount()
        if (account != null) {
            securePrefs.setOobeDone(account, true)
            val nickname = securePrefs.getNickname(account)
            if (nickname != null) {
                viewModel.updateNickname(nickname)
            }
            safeNavigate(R.id.action_OobeFragment_to_MainFragment)
        }
    }

    private fun safeNavigate(actionId: Int) {
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.OobeFragment) {
            navController.navigate(actionId)
        }
    }

    private fun observeLoginInOobe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loginState.collectLatest { state ->
                    val recyclerView = binding.oobeViewPager.getChildAt(0) as? RecyclerView
                    val authIndex = enabledSteps.indexOf(OobeStep.AUTH)
                    val authHolder = if (authIndex != -1) {
                        recyclerView?.findViewHolderForAdapterPosition(authIndex) as? AuthViewHolder
                    } else null

                    when (state) {
                        is MSNPLoginState.Loading, is MSNPLoginState.Reconnecting -> {
                            authHolder?.setLoading(true)
                        }
                        is MSNPLoginState.Success -> {
                            authHolder?.setLoading(false)
                            hideKeyboard()
                            val account = state.account.lowercase(Locale.ROOT).trim()
                            if (securePrefs.isOobeDone(account)) {
                                 safeNavigate(R.id.action_OobeFragment_to_MainFragment)
                            } else {
                                 navigateToNextStep()
                            }
                        }
                        is MSNPLoginState.Error -> {
                            authHolder?.setLoading(false)
                            
                            val displayMessage = when {
                                state.message == "ERROR_CONNECTION_FAILED" || state.message == "CONNECTION_FAILED" -> 
                                    getString(R.string.error_connection_failed)
                                state.message == "ERROR_INVALID_PASSWORD" || state.message == "AUTH_INVALID_PASSWORD" -> 
                                    getString(R.string.error_invalid_password)
                                else -> state.message
                            }
                            
                            authHolder?.authBinding?.tvError?.text = displayMessage
                            authHolder?.authBinding?.tvError?.visibility = View.VISIBLE
                        }
                        is MSNPLoginState.LoggedInElsewhere -> {
                            authHolder?.setLoading(false)
                            authHolder?.authBinding?.tvError?.text = getString(R.string.error_logged_in_another_device)
                            authHolder?.authBinding?.tvError?.visibility = View.VISIBLE
                        }
                        is MSNPLoginState.Idle -> {
                            authHolder?.setLoading(false)
                        }
                    }
                }
            }
        }
    }

    private fun showPopupMenu(view: View) {
        val popup = PopupMenu(requireContext(), view)
        popup.menu.add(0, 1, 0, getString(R.string.connection_settings))
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == 1) {
                showConnectionDialog()
                true
            } else false
        }
        popup.show()
    }

    private fun showConnectionDialog() {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val etHost = EditText(context).apply {
            hint = getString(R.string.host_hint)
            setText(securePrefs.serverAddress)
            setSingleLine(true)
        }

        val etNexusDomain = EditText(context).apply {
            hint = getString(R.string.nexus_hint)
            setText(securePrefs.nexusDomain)
            setSingleLine(true)
        }

        val etConfigUrl = EditText(context).apply {
            hint = "Config URL"
            setText(securePrefs.configUrl)
            setSingleLine(true)
        }

        layout.addView(etHost)
        layout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, 16) }) 
        layout.addView(etNexusDomain)
        layout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, 16) })
        layout.addView(etConfigUrl)

        AlertDialog.Builder(context)
            .setTitle(R.string.connection_settings)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val host = etHost.text.toString().trim()
                val nexusDomain = etNexusDomain.text.toString().trim()
                val configUrl = etConfigUrl.text.toString().trim()
                if (host.isNotEmpty() && nexusDomain.isNotEmpty() && configUrl.isNotEmpty()) {
                    securePrefs.serverAddress = host
                    securePrefs.nexusDomain = nexusDomain
                    securePrefs.configUrl = configUrl
                    Toast.makeText(context, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class OobeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = enabledSteps.size

        override fun getItemViewType(position: Int): Int = enabledSteps[position].ordinal

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (OobeStep.values()[viewType]) {
                OobeStep.WELCOME -> WelcomeViewHolder(FragmentOobeWelcomeBinding.inflate(inflater, parent, false))
                OobeStep.AUTH -> AuthViewHolder(FragmentAuthBinding.inflate(inflater, parent, false))
                OobeStep.NICKNAME -> NicknameViewHolder(FragmentOobeNicknameBinding.inflate(inflater, parent, false))
                OobeStep.NOTIFICATIONS -> PermissionViewHolder(FragmentOobePermissionsBinding.inflate(inflater, parent, false), OobeStep.NOTIFICATIONS.ordinal)
                OobeStep.PHONE -> PermissionViewHolder(FragmentOobePermissionsBinding.inflate(inflater, parent, false), OobeStep.PHONE.ordinal)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is WelcomeViewHolder -> holder.bind()
                is AuthViewHolder -> holder.bind()
                is PermissionViewHolder -> holder.bind(enabledSteps[position])
            }
        }
    }

    private inner class WelcomeViewHolder(val binding: FragmentOobeWelcomeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.ivLogo.setImageResource(AvatarUtils.getAppLogoResId(binding.root.context))
            binding.btnStart.setOnClickListener {
                hideKeyboard()
                navigateToNextStep()
            }
        }
    }

    private inner class AuthViewHolder(val authBinding: FragmentAuthBinding) : RecyclerView.ViewHolder(authBinding.root) {
        fun bind() {
            authBinding.ivLogo?.setImageResource(AvatarUtils.getAppLogoResId(authBinding.root.context))
            val savedAccount = viewModel.getSavedAccount()
            val savedPass = viewModel.getSavedPassword()
            if (savedAccount != null && savedPass != null) {
                authBinding.etAccount.setText(savedAccount)
                authBinding.etPassword.setText(savedPass)
                authBinding.cbRememberMe.isChecked = securePrefs.rememberMe
            }

            if (AppConfig.LIMPE_EXP) {
                authBinding.ivFooterLogo.visibility = View.VISIBLE
            }

            authBinding.btnLogin.setOnClickListener {
                val account = authBinding.etAccount.text.toString().trim()
                val password = authBinding.etPassword.text.toString().trim()
                val rememberMe = authBinding.cbRememberMe.isChecked
                
                if (account.isNotEmpty() && password.isNotEmpty()) {
                    authBinding.tvError.visibility = View.GONE
                    hideKeyboard()
                    viewModel.login(account, password, rememberMe)
                } else {
                    Toast.makeText(requireContext(), R.string.fill_all_fields, Toast.LENGTH_SHORT).show()
                }
            }

            authBinding.btnAuthMenu.setOnClickListener { showPopupMenu(it) }
        }

        fun setLoading(isLoading: Boolean) {
            if (isLoading) {
                authBinding.btnLogin.isEnabled = false
                authBinding.btnLogin.animate().alpha(0f).setDuration(250).start()
                authBinding.progressBar.apply {
                    alpha = 0f
                    scaleX = 0f
                    scaleY = 0f
                    visibility = View.VISIBLE
                    animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).setListener(null).start()
                }
            } else {
                authBinding.btnLogin.isEnabled = true
                authBinding.btnLogin.animate().alpha(1f).setDuration(250).start()
                authBinding.progressBar.animate().alpha(0f).scaleX(0f).scaleY(0f).setDuration(200)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            authBinding.progressBar.visibility = View.GONE
                        }
                    }).start()
            }
        }
    }

    private inner class NicknameViewHolder(val binding: FragmentOobeNicknameBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.btnNext.setOnClickListener {
                val nick = binding.etNickname.text.toString().trim()
                if (nick.isNotEmpty()) {
                    val account = securePrefs.getSavedAccount()
                    if (account != null) {
                        hideKeyboard()
                        securePrefs.setNickname(account, nick)
                        navigateToNextStep()
                    }
                } else {
                    Toast.makeText(requireContext(), R.string.enter_name_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private inner class PermissionViewHolder(val binding: FragmentOobePermissionsBinding, private val step: OobeStep? = null) : RecyclerView.ViewHolder(binding.root) {
        // Конструктор с type оставлен для совместимости если нужно, но лучше использовать step
        constructor(binding: FragmentOobePermissionsBinding, type: Int) : this(binding, OobeStep.values()[type])

        fun bind(currentStep: OobeStep) {
            if (currentStep == OobeStep.NOTIFICATIONS) {
                binding.ivIcon.setImageResource(R.drawable.notifications_24)
                binding.tvTitle.text = getString(R.string.oobe_notifications_title)
                binding.tvDescription.text = getString(R.string.oobe_notifications_description)
                binding.btnAllow.text = getString(R.string.allow)
                
                binding.btnAllow.setOnClickListener {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        navigateToNextStep()
                    }
                }
                binding.btnSkip.setOnClickListener {
                    navigateToNextStep()
                }
            } else if (currentStep == OobeStep.PHONE) {
                binding.ivIcon.setImageResource(R.drawable.ic_call_status)
                binding.tvTitle.text = getString(R.string.oobe_phone_title)
                binding.tvDescription.text = getString(R.string.oobe_phone_description)
                binding.btnAllow.text = getString(R.string.allow)

                binding.btnAllow.setOnClickListener {
                    phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                }
                binding.btnSkip.setOnClickListener {
                    navigateToNextStep()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
