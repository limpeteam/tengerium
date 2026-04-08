package com.limpe.tengerium

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.limpe.tengerium.data.MSNPRepository
import com.limpe.tengerium.data.security.SecurePrefs
import net.sqlcipher.database.SQLiteDatabase
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale
import kotlin.system.exitProcess

class TengeriumApp : Application() {
    
    lateinit var repository: MSNPRepository
        private set
    
    var isAppInForeground: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        
        if (isCrashProcess()) {
            return
        }
        
        setupCrashHandler()
        setupForegroundTracking()
        
        val securePrefs = SecurePrefs(this)
        
        // Миграция тем: если стояла старая нестандартная тема (индекс > 0), пересаживаем на Бирюзу (индекс 1)
        if (securePrefs.themePreset > 1) {
            securePrefs.themePreset = 1
        }

        applyLanguage(this, securePrefs.language)
        applyAppTheme(securePrefs)
        
        DynamicColors.applyToActivitiesIfAvailable(this, DynamicColorsOptions.Builder()
            .setPrecondition { _, _ -> 
                SecurePrefs(this).useMaterialYou 
            }
            .build())
        
        SQLiteDatabase.loadLibs(this)
        repository = MSNPRepository(this)

        // MSNPService.start(this) // Перенесено в MainActivity для предотвращения ForegroundServiceStartNotAllowedException
        
        // Автоматический вход только если OOBE завершен для текущего аккаунта
        val savedAcc = securePrefs.getSavedAccount()
        if (savedAcc != null && securePrefs.isOobeDone(savedAcc)) {
            repository.autoLogin()
        }
    }

    private fun setupForegroundTracking() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0

            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                isAppInForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities <= 0) {
                    isAppInForeground = false
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun applyAppTheme(prefs: SecurePrefs) {
        if (prefs.followSystemTheme) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } else {
            if (prefs.darkTheme) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
    }

    private fun isCrashProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            val pid = android.os.Process.myPid()
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.find { it.pid == pid }?.processName ?: ""
        }
        return processName.endsWith(":crash")
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                val stackTrace = sw.toString()

                val intent = Intent(this, CrashReportActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("stacktrace", stackTrace)
                }
                startActivity(intent)
                
                exitProcess(1)
            } catch (_: Exception) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    companion object {
        fun applyLanguage(context: Context, lang: String) {
            // Установка через AppCompatDelegate для системной поддержки.
            // Это современный способ, который автоматически обрабатывает context wrapping в Activity.
            val appLocale: LocaleListCompat = if (lang == "system") {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(lang)
            }
            AppCompatDelegate.setApplicationLocales(appLocale)
            
            // Для Application context и совместимости со старыми API обновляем конфигурацию вручную,
            // но НЕ заменяем базовый контекст через createConfigurationContext в attachBaseContext,
            // так как это вызывает ClassCastException на некоторых OEM-устройствах (ActivityImpl).
            if (lang != "system") {
                val locale = Locale(lang)
                Locale.setDefault(locale)
                val config = Configuration(context.resources.configuration)
                config.setLocale(locale)
                config.setLayoutDirection(locale)
                @Suppress("DEPRECATION")
                context.resources.updateConfiguration(config, context.resources.displayMetrics)
            }
        }
    }
}
