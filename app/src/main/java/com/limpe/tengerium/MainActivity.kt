package com.limpe.tengerium

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.limpe.tengerium.data.MSNPLoginState
import com.limpe.tengerium.data.MSNPRepository
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.databinding.ActivityMainBinding
import com.limpe.tengerium.databinding.ViewInAppNotificationBinding
import com.limpe.tengerium.ui.AvatarUtils
import com.limpe.tengerium.ui.FormattingUtils
import com.limpe.tengerium.ui.MainFragment
import com.limpe.tengerium.util.AnimationHelper
import com.limpe.tengerium.util.UiConstants
import com.limpe.tengerium.util.VibrationHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var notificationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkTimeBomb()) return

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val repository = (application as TengeriumApp).repository
        val securePrefs = SecurePrefs(this)
        
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.loginState.collectLatest { state ->
                    handleLoginStateDude(state)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.incomingMessageFlow.collectLatest { incoming ->
                    // Если пришел Будильник (Nudge)
                    if (incoming.message == "[NUDGE]") {
                        if (securePrefs.notifyNudge) {
                            AnimationHelper.animateNudge(binding.navHostFragmentContentMain)
                            if (securePrefs.vibrationEnabled) {
                                VibrationHelper.vibrateNudge(this@MainActivity)
                            }
                        } else {
                            // Если Nudge отключен, но сообщения включены - показываем как обычное
                            if (securePrefs.notifyMessages) {
                                showInAppNotification(repository, incoming)
                            }
                        }
                    } else if (securePrefs.notifyMessages) {
                        if (repository.activeChatAccount?.lowercase(Locale.ROOT) != incoming.senderAccount.lowercase(Locale.ROOT)) {
                            showInAppNotification(repository, incoming)
                        }
                    }
                }
            }
        }

        if (savedInstanceState == null) {
            intent?.getStringExtra("contact_account")?.let { account ->
                navigateToChat(account)
            }
        }
    }

    private fun handleLoginStateDude(state: MSNPLoginState) {
        when (state) {
            is MSNPLoginState.Reconnecting -> {
                binding.dudeReconnectingLayout.visibility = View.VISIBLE
            }
            is MSNPLoginState.Success -> {
                binding.dudeReconnectingLayout.visibility = View.GONE
            }
            is MSNPLoginState.LoggedInElsewhere -> {
                binding.dudeReconnectingLayout.visibility = View.GONE
                handleLoggedInElsewhereDude()
            }
            is MSNPLoginState.Error -> {
                binding.dudeReconnectingLayout.visibility = View.GONE
                if (state.message == "AUTH_INVALID_PASSWORD") {
                    handleSessionExpired()
                }
            }
            else -> {
                binding.dudeReconnectingLayout.visibility = View.GONE
            }
        }
    }

    private fun handleLoggedInElsewhereDude() {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        if (navController.currentDestination?.id != R.id.AuthFragment) {
            navController.navigate(R.id.AuthFragment)
            
            AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.error_logged_in_another_device)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.getStringExtra("contact_account")?.let { account ->
            navigateToChat(account)
        }
    }

    private fun navigateToChat(account: String) {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
        val currentFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()

        if (currentFragment is MainFragment && currentFragment.isTablet) {
            currentFragment.openChatOnTablet(account)
        } else {
            val bundle = bundleOf("account" to account)
            navController.navigate(R.id.MainFragment, bundle)
        }
    }

    private fun showInAppNotification(repository: MSNPRepository, incoming: com.limpe.tengerium.data.MessageManager.IncomingMessage) {
        notificationJob?.cancel()
        notificationJob = lifecycleScope.launch {
            val viewBinding = ViewInAppNotificationBinding.inflate(LayoutInflater.from(this@MainActivity), binding.notificationContainer, false)
            
            viewBinding.tvNotificationName.text = FormattingUtils.formatBBCode(incoming.nickname.ifEmpty { incoming.senderAccount })
            viewBinding.tvNotificationMessage.text = if (incoming.message == "[NUDGE]") getString(R.string.nudge_received) else incoming.message
            
            val contact = repository.contacts.value.find { it.account.lowercase(Locale.ROOT) == incoming.senderAccount.lowercase(Locale.ROOT) }
            AvatarUtils.loadAvatar(viewBinding.ivNotificationAvatar, contact?.avatarUrl, incoming.senderAccount)

            viewBinding.root.setOnClickListener {
                navigateToChat(incoming.senderAccount)
                binding.notificationContainer.removeView(viewBinding.root)
                notificationJob?.cancel()
            }

            binding.notificationContainer.removeAllViews()
            binding.notificationContainer.addView(viewBinding.root)

            AnimationHelper.animateNotificationIn(viewBinding.root)

            delay(UiConstants.NOTIFICATION_DISPLAY_DELAY)

            AnimationHelper.animateNotificationOut(viewBinding.root) {
                binding.notificationContainer.removeView(viewBinding.root)
            }
        }
    }

    private fun handleSessionExpired() {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        if (navController.currentDestination?.id != R.id.AuthFragment) {
            navController.navigate(R.id.AuthFragment)
            
            AlertDialog.Builder(this)
                .setTitle(R.string.session_expired_title)
                .setMessage(R.string.session_expired_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun checkTimeBomb(): Boolean {
        val bombDate = Calendar.getInstance().apply {
            set(2026, Calendar.AUGUST, 10, 0, 0, 0)
        }
        
        if (System.currentTimeMillis() >= bombDate.timeInMillis) {
            AlertDialog.Builder(this)
                .setTitle(R.string.app_expired_title)
                .setMessage(R.string.app_expired_message)
                .setCancelable(false)
                .setPositiveButton(R.string.update) { _, _ ->
                }
                .show()
            return true
        }
        return false
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
