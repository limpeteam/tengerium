package com.limpe.tengerium.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.MSNPLoginState
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.databinding.FragmentAuthBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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

        binding.btnAuthMenu.setOnClickListener {
            showPopupMenu(it)
        }

        binding.btnLogin.setOnClickListener {
            val account = binding.etAccount.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val rememberMe = binding.cbRememberMe.isChecked

            if (account.isEmpty() || password.isEmpty()) {
                Toast.makeText(requireContext(), R.string.fill_all_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.tvError.visibility = View.GONE
            viewModel.login(account, password, rememberMe)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loginState.collectLatest { state ->
                    when (state) {
                        is MSNPLoginState.Loading, is MSNPLoginState.Reconnecting -> {
                            binding.loadingLayout.visibility = View.VISIBLE
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
                        is MSNPLoginState.Idle -> {
                            binding.loadingLayout.visibility = View.GONE
                            binding.btnLogin.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }

        // Заполняем данные только если включен Remember Me
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
