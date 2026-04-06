package com.limpe.tengerium.data.protocol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Vibrator
import android.os.VibrationEffect
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.limpe.tengerium.MainActivity
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.MSNPLoginState
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.util.NotificationUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

class MSNPService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isForeground = false
    private var isRepositoryObserved = false
    
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: Any? = null
    private var lastStatusBeforeCall: String = "NLN"
    
    private lateinit var securePrefs: SecurePrefs
    private val contactStatuses = mutableMapOf<String, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Вызываем startForeground немедленно в onCreate
        updateForegroundNotification(null)
        
        securePrefs = SecurePrefs(this)
        setupPhoneStateListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRepositoryObserved) {
            observeRepository()
            isRepositoryObserved = true
        }
        return START_STICKY
    }

    private fun handleCallState(state: Int) {
        if (!securePrefs.phoneStatusEnabled) return

        val repository = (application as TengeriumApp).repository
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_RINGING -> {
                val currentStatus = repository.currentStatus
                if (currentStatus != "PHN") {
                    lastStatusBeforeCall = currentStatus
                    Log.d("MSNPService", "Call started. Changing status to PHN. Previous: $lastStatusBeforeCall")
                    repository.changeStatus("PHN")
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (repository.currentStatus == "PHN") {
                    Log.d("MSNPService", "Call ended. Restoring status to $lastStatusBeforeCall")
                    repository.changeStatus(lastStatusBeforeCall)
                }
            }
        }
    }

    private fun setupPhoneStateListener() {
        val tm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getSystemService(TelephonyManager::class.java)
        } else {
            getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        }
        if (tm == null) return
        
        telephonyManager = tm
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state)
                    }
                }
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(this), callback)
                telephonyCallback = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(state)
                    }
                }
                phoneStateListener = listener
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (_: SecurityException) {
            Log.w("MSNPService", "READ_PHONE_STATE permission not granted. Call state listener disabled.")
            phoneStateListener = null
            telephonyCallback = null
        } catch (e: Exception) {
            Log.e("MSNPService", "Failed to setup PhoneStateListener: ${e.message}")
            phoneStateListener = null
            telephonyCallback = null
        }
    }

    private fun observeRepository() {
        val repository = (application as TengeriumApp).repository
        serviceScope.launch {
            repository.loginState
                .map { state ->
                    when (state) {
                        is MSNPLoginState.Success -> state.nickname
                        is MSNPLoginState.Loading, is MSNPLoginState.Reconnecting -> getString(R.string.status_trying_to_connect)
                        else -> null
                    }
                }
                .distinctUntilChanged()
                .collectLatest { statusText ->
                    updateForegroundNotification(statusText)
                }
        }
        
        serviceScope.launch {
            repository.contacts
                .map { list -> 
                    // Преобразуем список в мапу account -> status для эффективного сравнения
                    list.associate { it.account.lowercase(Locale.ROOT) to it.status }
                }
                .distinctUntilChanged()
                .collectLatest { currentMap ->
                    if (!securePrefs.notifyLogin) {
                        contactStatuses.clear()
                        contactStatuses.putAll(currentMap)
                        return@collectLatest
                    }
                    
                    // проверяем только изменения, а не перебираем всё каждый раз
                    if (contactStatuses.isEmpty()) {
                        contactStatuses.putAll(currentMap)
                        return@collectLatest
                    }

                    currentMap.forEach { (account, newStatus) ->
                        val oldStatus = contactStatuses[account]
                        
                        if (oldStatus != newStatus) {
                            // Если старый статус был оффлайн, а новый — любой онлайн
                            val wasOffline = oldStatus == null || oldStatus == MSNPProto.Status.OFFLINE
                            val isOnline = newStatus != MSNPProto.Status.OFFLINE
                            
                            if (wasOffline && isOnline) {
                                val contact = repository.contacts.value.find { it.account.lowercase(Locale.ROOT) == account }
                                val nickname = contact?.nickname?.ifEmpty { account } ?: account
                                showEventNotification(
                                    this@MSNPService,
                                    getString(R.string.app_name),
                                    getString(R.string.user_is_now_online, nickname),
                                    "LOGIN_$account"
                                )
                            }
                        }
                    }
                    contactStatuses.clear()
                    contactStatuses.putAll(currentMap)
                }
        }
    }

    private fun updateForegroundNotification(statusText: String?) {
        val notification = createForegroundNotification(statusText)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID_FOREGROUND, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID_FOREGROUND, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e("MSNPService", "Failed to start foreground service: ${e.message}")
        }
    }

    private fun createForegroundNotification(statusText: String?): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = if (statusText != null) {
            if (statusText == getString(R.string.status_trying_to_connect)) statusText else getString(R.string.logged_in_as, statusText)
        } else {
            getString(R.string.status_offline)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(NotificationUtils.getSmallIconId(this))
            .setOngoing(true)
            .setSilent(true) // Делаем уведомление всегда беззвучным
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager == null) return
            
            val fgChannel = NotificationChannel(
                CHANNEL_ID_FOREGROUND,
                getString(R.string.channel_connection_status),
                NotificationManager.IMPORTANCE_LOW // Низкий приоритет = без звука
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(fgChannel)

            val msgChannel = NotificationChannel(
                CHANNEL_ID_MESSAGES,
                getString(R.string.channel_messages),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableLights(true)
                enableVibration(true)
            }
            manager.createNotificationChannel(msgChannel)

            val eventChannel = NotificationChannel(
                CHANNEL_ID_EVENTS,
                getString(R.string.channel_contact_events),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                enableLights(true)
                enableVibration(true)
            }
            manager.createNotificationChannel(eventChannel)
        }
    }

    override fun onDestroy() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let {
                    telephonyManager?.unregisterTelephonyCallback(it)
                }
            } else {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (_: Exception) {
            // Ignore
        }
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID_FOREGROUND = 1001
        private const val NOTIFICATION_ID_EVENT = 1002
        private const val CHANNEL_ID_FOREGROUND = "msnp_connection"
        private const val CHANNEL_ID_MESSAGES = "messages"
        private const val CHANNEL_ID_EVENTS = "events"
        
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val ACTION_REPLY = "com.limpe.tengerium.ACTION_REPLY"

        fun start(context: Context) {
            val intent = Intent(context, MSNPService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("MSNPService", "Foreground service start failed, falling back to background start: ${e.message}")
                try {
                    context.startService(intent)
                } catch (e2: Exception) {
                    Log.e("MSNPService", "Background service start also failed: ${e2.message}")
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MSNPService::class.java)
            context.stopService(intent)
        }

        fun showMessageNotification(context: Context, nickname: String, message: String, sender: String) {
            val prefs = SecurePrefs(context)
            if (!prefs.notifyMessages) return
            
            val normalizedSender = sender.lowercase(Locale.ROOT).trim()
            if (prefs.isChatMuted(normalizedSender)) return

            val app = context.applicationContext as? TengeriumApp
            val isAppForeground = app?.isAppInForeground ?: false
            val activeChat = app?.repository?.activeChatAccount
            
            // Если мы прямо сейчас в чате с этим человеком, системное уведомление не нужно
            if (activeChat?.lowercase(Locale.ROOT)?.trim() == normalizedSender) {
                return
            }

            val notificationManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(NotificationManager::class.java)
            } else {
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            } ?: return
            
            val notificationId = normalizedSender.hashCode()
            
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("contact_account", normalizedSender)
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context, notificationId, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
                .setLabel(context.getString(R.string.type_reply))
                .build()

            val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
                action = ACTION_REPLY
                putExtra("contact_account", normalizedSender)
            }

            val replyPendingIntent = PendingIntent.getBroadcast(
                context, notificationId, replyIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
            )

            val replyAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send,
                context.getString(R.string.reply),
                replyPendingIntent
            ).addRemoteInput(remoteInput).build()

            val effectiveNickname = nickname.ifEmpty { normalizedSender }
            val displayMessage = if (message == "[NUDGE]") {
                context.getString(R.string.nudge_received_sys, effectiveNickname)
            } else {
                message
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
                .setSmallIcon(NotificationUtils.getSmallIconId(context))
                .setContentTitle(effectiveNickname)
                .setContentText(displayMessage)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(replyAction)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

            if (isAppForeground) {
                // Если приложение активно, делаем системное уведомление тихим, чтобы не мешать in-app
                builder.setSilent(true)
            } else {
                // Если приложение в фоне, используем настройки звука и вибрации
                if (prefs.vibrationEnabled) {
                    builder.setDefaults(NotificationCompat.DEFAULT_ALL)
                } else {
                    builder.setDefaults(NotificationCompat.DEFAULT_LIGHTS or NotificationCompat.DEFAULT_SOUND)
                    builder.setVibrate(longArrayOf(0L))
                }
            }

            notificationManager.notify(notificationId, builder.build())

            // Дополнительная вибрация для NUDGE если разрешено, но только если не в фокусе чата
            if (message == "[NUDGE]" && prefs.notifyNudge && prefs.vibrationEnabled && !isAppForeground) {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    context.getSystemService(Vibrator::class.java)
                } else {
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                if (vibrator != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200, 100, 200), -1))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(longArrayOf(0, 200, 100, 200, 100, 200), -1)
                    }
                }
            }
        }

        fun cancelMessageNotification(context: Context, sender: String) {
            val notificationManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(NotificationManager::class.java)
            } else {
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            } ?: return
            notificationManager.cancel(sender.lowercase(Locale.ROOT).trim().hashCode())
        }

        fun cancelAllEventNotifications(context: Context) {
            val notificationManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(NotificationManager::class.java)
            } else {
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            } ?: return
            // cancelAll() убирает все уведомления, кроме тех, что в статус-баре (foreground service)
            // Это то что нужно при входе в приложение
            notificationManager.cancelAll()
        }

        fun showEventNotification(context: Context, title: String, message: String, tag: String) {
            val prefs = SecurePrefs(context)
            val notificationManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(NotificationManager::class.java)
            } else {
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            } ?: return
            
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, tag.hashCode(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID_EVENTS)
                .setSmallIcon(NotificationUtils.getSmallIconId(context))
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            if (prefs.vibrationEnabled) {
                builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            } else {
                builder.setDefaults(NotificationCompat.DEFAULT_LIGHTS or NotificationCompat.DEFAULT_SOUND)
                builder.setVibrate(longArrayOf(0L))
            }

            notificationManager.notify(tag, NOTIFICATION_ID_EVENT, builder.build())
            
            if (tag.startsWith("NUDGE") && prefs.notifyNudge && prefs.vibrationEnabled) {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    context.getSystemService(Vibrator::class.java)
                } else {
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                if (vibrator != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200, 100, 200), -1))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(longArrayOf(0, 200, 100, 200, 100, 200), -1)
                    }
                }
            }
        }
    }
}
