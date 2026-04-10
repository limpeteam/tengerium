package com.limpe.tengerium.ui

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.AppConfig
import com.limpe.tengerium.data.MSNPLoginState
import com.limpe.tengerium.data.protocol.MSNPService
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.databinding.FragmentAuthBinding
import com.limpe.tengerium.util.AnimationHelper
import com.limpe.tengerium.util.ConnectionDialogHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class AuthFragment : Fragment() {

    private var _binding: FragmentAuthBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AuthViewModel by viewModels {
        val app = requireActivity().application as TengeriumApp
        AuthViewModel.Factory(app.repository, app)
    }

    private lateinit var securePrefs: SecurePrefs

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAuthBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        securePrefs = SecurePrefs(requireContext())

        if (AppConfig.USENEWLOGINEXP) {
            setupNewLoginExperience()
        }

        setupValidation()

        binding.btnAuthMenu.setOnClickListener {
            showPopupMenu(it)
        }

        binding.btnLogin.setOnClickListener {
            val account = binding.etAccount.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val rememberMe = binding.cbRememberMe.isChecked

            if (!validateInputs(account, password)) {
                return@setOnClickListener
            }

            securePrefs.rememberMe = rememberMe
            binding.tvError.visibility = View.GONE
            
            viewModel.login(account, password, rememberMe)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loginState.collectLatest { state ->
                    when (state) {
                        is MSNPLoginState.Loading -> {
                            binding.loadingLayout.visibility = View.VISIBLE
                            binding.tvLoadingStatus.visibility = View.GONE
                            binding.btnLogin.visibility = View.GONE
                            binding.tvError.visibility = View.GONE
                        }
                        is MSNPLoginState.Reconnecting -> {
                            binding.loadingLayout.visibility = View.VISIBLE
                            binding.tvLoadingStatus.visibility = View.VISIBLE
                            binding.tvLoadingStatus.text = getString(R.string.reconnecting_in, state.secondsRemaining)
                            binding.btnLogin.visibility = View.GONE
                            binding.tvError.visibility = View.GONE
                        }
                        is MSNPLoginState.Success -> {
                            binding.loadingLayout.visibility = View.GONE
                            binding.btnLogin.visibility = View.VISIBLE
                            findNavController().navigate(R.id.action_AuthFragment_to_MainFragment)
                        }
                        is MSNPLoginState.Error -> {
                            binding.loadingLayout.visibility = View.GONE
                            binding.btnLogin.visibility = View.VISIBLE
                            
                            val displayMessage = when {
                                state.message == "ERROR_CONNECTION_FAILED" || state.message == "CONNECTION_FAILED" -> 
                                    getString(R.string.error_connection_failed)
                                state.message == "ERROR_INVALID_PASSWORD" || state.message == "AUTH_INVALID_PASSWORD" -> 
                                    getString(R.string.error_invalid_password)
                                else -> state.message
                            }
                            
                            binding.tvError.text = displayMessage
                            binding.tvError.visibility = View.VISIBLE
                        }
                        is MSNPLoginState.LoggedInElsewhere -> {
                            binding.loadingLayout.visibility = View.GONE
                            binding.btnLogin.visibility = View.VISIBLE
                            binding.tvError.text = getString(R.string.error_logged_in_another_device)
                            binding.tvError.visibility = View.VISIBLE
                        }
                        is MSNPLoginState.NoInternet -> {
                            binding.loadingLayout.visibility = View.GONE
                            binding.btnLogin.visibility = View.VISIBLE
                            binding.tvError.text = getString(R.string.no_internet)
                            binding.tvError.visibility = View.VISIBLE
                        }
                        is MSNPLoginState.Idle -> {
                            binding.loadingLayout.visibility = View.GONE
                            binding.btnLogin.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }

        binding.cbRememberMe.isChecked = securePrefs.rememberMe
        if (securePrefs.rememberMe) {
            val savedAcc = viewModel.getSavedAccount()
            val savedPass = viewModel.getSavedPassword()
            if (savedAcc != null && savedPass != null) {
                binding.etAccount.setText(savedAcc)
                binding.etPassword.setText(savedPass)
            }
        }
    }

    private fun setupNewLoginExperience() {
        binding.tvAppName.visibility = View.GONE
        
        // Показываем футер с логотипом Limpe
        binding.layoutFooter.visibility = View.VISIBLE
        binding.ivFooterLogo.visibility = View.VISIBLE

        AnimationHelper.animateLoginLogo(binding.ivLogo)

        val inputViews = listOf(
            binding.tilAccount,
            binding.tilPassword,
            binding.cbRememberMe,
            binding.flLoginContainer,
            binding.layoutFooter
        )
        AnimationHelper.animateLoginInputs(inputViews)
    }

    private fun setupValidation() {
        binding.etAccount.doAfterTextChanged {
            binding.tilAccount.error = null
        }
        binding.etPassword.doAfterTextChanged {
            binding.tilPassword.error = null
        }
    }

    private fun validateInputs(account: String, password: String): Boolean {
        var isValid = true

        if (account.isEmpty()) {
            binding.tilAccount.error = getString(R.string.fill_all_fields)
            isValid = false
        } else if (!Patterns.EMAIL_ADDRESS.matcher(account).matches()) {
            binding.tilAccount.error = getString(R.string.invalid_email)
            isValid = false
        }

        if (password.isEmpty()) {
            binding.tilPassword.error = getString(R.string.fill_all_fields)
            isValid = false
        }

        return isValid
    }

    private fun showPopupMenu(view: View) {
        val popup = PopupMenu(requireContext(), view)
        popup.menu.add(0, 1, 0, getString(R.string.connection_settings))
        popup.menu.add(0, 2, 1, getString(R.string.close_app))
        
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    ConnectionDialogHelper.showConnectionDialog(requireContext(), securePrefs)
                    true
                }
                2 -> {
                    MSNPService.stop(requireContext())
                    requireActivity().finishAffinity()
                    exitProcess(0)
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
