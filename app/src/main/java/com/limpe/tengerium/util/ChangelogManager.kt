package com.limpe.tengerium.util

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.PackageInfoCompat
import com.limpe.tengerium.R
import com.limpe.tengerium.data.security.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Менеджер для загрузки и отображения списка изменений (Changelog).
 */
object ChangelogManager {

    private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/lednikofff/tengerium/releases"

    /**
     * Проверяет обновление версии и показывает чейнджлог при необходимости.
     */
    suspend fun checkAndShowChangelog(context: Context, securePrefs: SecurePrefs) {
        val packageInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        } ?: return

        val currentVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo).toInt()
        val lastVersionCode = securePrefs.lastUsedVersionCode

        // Если это первый запуск или версия обновилась
        if (lastVersionCode == 0 || currentVersionCode > lastVersionCode) {
            val changelog = fetchChangelog(packageInfo.versionName ?: "")
            if (changelog != null) {
                withContext(Dispatchers.Main) {
                    showChangelogDialog(context, changelog)
                    securePrefs.lastUsedVersionCode = currentVersionCode
                }
            } else {
                // Если не удалось загрузить (нет инета), попробуем в следующий раз
                // Не обновляем lastUsedVersionCode, чтобы показать при наличии связи
            }
        }
    }

    private suspend fun fetchChangelog(currentVersionName: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_RELEASES_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val releases = JSONArray(response)
                
                for (i in 0 until releases.length()) {
                    val release = releases.getJSONObject(i)
                    val tagName = release.getString("tag_name")
                    
                    // Ищем описание для текущей версии (по тегу)
                    if (tagName.contains(currentVersionName) || currentVersionName.contains(tagName)) {
                        return@withContext formatChangelog(release.getString("body"))
                    }
                }
                
                // Если точного совпадения нет, берем самый свежий релиз
                if (releases.length() > 0) {
                    return@withContext formatChangelog(releases.getJSONObject(0).getString("body"))
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun formatChangelog(rawBody: String): String {
        return rawBody
            .replace("\r\n", "\n")
            // Простейший Markdown парсинг для AlertDialog
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1") 
            .replace(Regex("### (.*)"), "$1")
            .replace(Regex("## (.*)"), "$1")
            .replace("[*]", "•")
            .replace("* ", "• ")
            .trim()
    }

    private fun showChangelogDialog(context: Context, changelog: String) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.update_changelog))
            .setMessage(changelog)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
