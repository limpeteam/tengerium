package com.limpe.tengerium.data

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// Класс-кладовщик. Следит, чтобы у каждого контакта была своя мордашка, и не качал одно и то же по сто раз.
class AvatarManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sessionManager: SessionManager
) {
    private val TAG = "AvatarManager"
    
    // Кэш соответствия SHA1 -> Путь к файлу
    private val sha1ToFileCache = ConcurrentHashMap<String, String>()
    
    // Чтобы не долбить сервер запросами одной и той же авы, пока она грузится.
    private val inProgressSha1 = ConcurrentHashMap<String, Boolean>()
    
    // Очередь на получение аватарок.
    private val avatarRequestChannel = Channel<Pair<String, String>>(Channel.UNLIMITED)
    
    // Папка, где лежат наши сокровища.
    private val avatarsDir = File(context.cacheDir, "avatars").apply { if (!exists()) mkdirs() }

    init {
        loadExistingAvatars()
        startWorker()
    }

    private fun loadExistingAvatars() {
        avatarsDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("av_")) {
                val sha1 = file.name.removePrefix("av_").removeSuffix(".dat")
                    .replace("_", "/").replace("-", "+")
                sha1ToFileCache[sha1] = file.absolutePath
            }
        }
    }

    // Фоновый рабочий.
    private fun startWorker() {
        scope.launch {
            val semaphore = Semaphore(3)
            for (request in avatarRequestChannel) {
                val (account, msnObject) = request
                val sha1d = extractAttr(msnObject, "SHA1D") ?: continue
                
                launch {
                    semaphore.withPermit {
                        try {
                            // Если файл уже есть в кэше — мы об этом узнаем раньше, но на всякий случай проверяем и тут
                            if (sha1ToFileCache.containsKey(sha1d)) {
                                return@withPermit
                            }
                            
                            val session = sessionManager.getOrOpenSession(account)
                            if (session != null) {
                                Log.d(TAG, "Запрашиваем аватар для $account (SHA1: $sha1d)")
                                session.requestAvatar(account, msnObject)
                            } else {
                                inProgressSha1.remove(sha1d)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Воркер споткнулся на $account", e)
                            inProgressSha1.remove(sha1d)
                        }
                    }
                }
                delay(300)
            }
        }
    }

    fun getAvatarBySha1(sha1: String): String? {
        return sha1ToFileCache[sha1]
    }

    fun requestAvatar(account: String, msnObject: String) {
        val sha1d = extractAttr(msnObject, "SHA1D") ?: return
        
        // Если уже есть в кэше, ничего не делаем (репозиторий сам должен был проверить)
        if (sha1ToFileCache.containsKey(sha1d)) return
        
        // Если уже в процессе загрузки, не дублируем
        if (inProgressSha1.putIfAbsent(sha1d, true) != null) return
        
        scope.launch { avatarRequestChannel.send(account to msnObject) }
    }

    fun saveAvatar(bytes: ByteArray, sha1d: String): String {
        val fileName = getFileNameForSha1(sha1d)
        val file = File(avatarsDir, fileName)
        try {
            if (!file.exists() || file.length() != bytes.size.toLong()) {
                file.writeBytes(bytes)
                Log.d(TAG, "Аватар сохранен: $fileName (${bytes.size} байт)")
            }
            sha1ToFileCache[sha1d] = file.absolutePath
            inProgressSha1.remove(sha1d)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при сохранении аватара $fileName", e)
        }
        return file.absolutePath
    }

    fun saveOwnAvatar(bytes: ByteArray): String {
        val sha1 = calculateSha1(bytes)
        return saveAvatar(bytes, sha1)
    }

    fun getAvatarFile(msnObject: String?): File? {
        if (msnObject.isNullOrBlank()) return null
        val sha1d = extractAttr(msnObject, "SHA1D") ?: return null
        val path = sha1ToFileCache[sha1d] ?: return null
        val file = File(path)
        return if (file.exists() && file.length() > 0) file else null
    }

    private fun getFileNameForSha1(sha1d: String): String {
        val safeSha1 = sha1d.replace("/", "_").replace("+", "-").replace("=", "").trim()
        return "av_$safeSha1.dat"
    }

    fun extractAttr(xml: String, attr: String): String? {
        val regex = """$attr\s*=\s*["']([^"']*)["']""".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(xml)
        val result = match?.groupValues?.get(1)
        return result?.let { 
            if (it.contains("%")) {
                try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
            } else it
        }
    }

    fun calculateSha1(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        return Base64.encodeToString(md.digest(bytes), Base64.NO_WRAP)
    }
}
