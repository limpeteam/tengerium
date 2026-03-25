package com.limpe.tengerium

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.limpe.tengerium.data.AppConfig
import com.limpe.tengerium.data.MSNPRepository
import com.limpe.tengerium.data.protocol.MSNPService
import com.limpe.tengerium.data.security.SecurePrefs
import net.sqlcipher.database.SQLiteDatabase
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale
import kotlin.system.exitProcess

class TengeriumApp : Application() {
    
    lateinit var repository: MSNPRepository
        private set

    override fun onCreate() {
        super.onCreate()
        
        if (isCrashProcess()) {
            return
        }
        
        setupCrashHandler()
        
        val securePrefs = SecurePrefs(this)
        applyLanguage(this, securePrefs.language)
        applyAppTheme(securePrefs)
        
        DynamicColors.applyToActivitiesIfAvailable(this, DynamicColorsOptions.Builder()
            .setPrecondition { _, _ -> 
                SecurePrefs(this).useMaterialYou 
            }
            .build())
        
        SQLiteDatabase.loadLibs(this)
        repository = MSNPRepository(this)
        
        AppConfig.showDebugToasts = securePrefs.debugEnabled

        MSNPService.start(this)
        
        // Автоматический вход только если OOBE завершен для текущего аккаунта
        val savedAcc = securePrefs.getSavedAccount()
        if (savedAcc != null && securePrefs.isOobeDone(savedAcc)) {
            repository.autoLogin()
        }
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
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
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
            } catch (e: Exception) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(wrapContext(base))
    }

    companion object {
        /**
         * Обертка контекста для применения языка. 
         * Используется в attachBaseContext приложения и всех Activity.
         */
        fun wrapContext(base: Context): Context {
            val securePrefs = SecurePrefs(base)
            val lang = securePrefs.language
            if (lang == "system") return base
            
            val locale = Locale(lang)
            Locale.setDefault(locale)
            
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            config.setLayoutDirection(locale)
            
            return base.createConfigurationContext(config)
        }

        fun applyLanguage(context: Context, lang: String) {
            // Установка через AppCompatDelegate для системной поддержки
            val appLocale: LocaleListCompat = if (lang == "system") {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(lang)
            }
            AppCompatDelegate.setApplicationLocales(appLocale)
            if (lang != "system") {
                val locale = Locale(lang)
                Locale.setDefault(locale)
                val config = Configuration(context.resources.configuration)
                config.setLocale(locale)
                config.setLayoutDirection(locale)
                context.resources.updateConfiguration(config, context.resources.displayMetrics)
            }
        }
    }
}
