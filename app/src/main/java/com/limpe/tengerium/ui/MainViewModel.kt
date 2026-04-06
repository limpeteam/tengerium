package com.limpe.tengerium.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.limpe.tengerium.data.MSNPRepository
import com.limpe.tengerium.data.db.MessageEntity
import com.limpe.tengerium.data.protocol.MSNPService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Главная вьюмоделька, которая рулит всем движем на основных экранах.
// Чтобы фрагменты не мучали репозиторий напрямую, всё идет через этого посредника.
class MainViewModel(private val repository: MSNPRepository) : ViewModel() {

    // Стягиваем данные из репозитория. Тут всё: от списка контактов до того, не вылетел ли логин.
    val contacts = repository.contacts
    val loginState = repository.loginState
    val myStatus = repository.myStatus
    val myPersonalMessage = repository.myPersonalMessage
    val incomingMessageFlow = repository.incomingMessageFlow

    // Костыли (зачеркнуто) — важные переменные, чтобы списки не прыгали, 
    // когда юзер скачет между вкладками. Запоминаем, где он остановился.
    var contactsScrollPosition: Int = 0
    var contactsScrollOffset: Int = 0
    var chatsScrollPosition: Int = 0
    var chatsScrollOffset: Int = 0
    var profileScrollY: Int = 0
    
    // Запоминаем последнюю открытую вкладку, чтобы при возврате (например, из веб-сервиса)
    // не кидало на вкладку по умолчанию.
    var lastMainTab: Int = -1

    // Говорим приложению, с кем мы сейчас трещим. 
    // Заодно помечаем сообщения как прочитанные, чтобы счетчик не мозолил глаза.
    fun setActiveChat(account: String?) {
        repository.activeChatAccount = account
        if (account != null) {
            repository.markAsRead(account)
            MSNPService.cancelMessageNotification(repository.context, account)
        }
    }

    // Кидаем мессадж в бездну интернета.
    fun sendMessage(target: String, text: String) {
        viewModelScope.launch {
            repository.sendMessage(target, text)
        }
    }

    // Достаем историю переписки. Всё летит через Flow, так что обновится само.
    fun getMessages(contactAccount: String): Flow<List<MessageEntity>> {
        return repository.getMessages(contactAccount)
    }

    // Меняем статус (В сети, Занят и прочее). Пускай все знают, что мы делаем.
    fun setStatus(status: String) {
        repository.setStatus(status)
    }

    // Всё, пацаны, я ливаю.
    fun logout() {
        repository.logout()
    }

    // --- Методы для работы с хранилищем (используются в настройках) ---

    suspend fun getCacheSize(context: Context): Long = withContext(Dispatchers.IO) {
        getFolderSize(context.cacheDir) + (context.externalCacheDir?.let { getFolderSize(it) } ?: 0L)
    }

    suspend fun getHistorySize(): Long = withContext(Dispatchers.IO) {
        // Примерный расчет размера БД или просто запрашиваем из репозитория/базы
        // В данном случае просто возвращаем 0 или размер файла БД если знаем путь
        val dbFile = repository.context.getDatabasePath("tengerium.db")
        if (dbFile.exists()) dbFile.length() else 0L
    }

    fun clearCache(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteFolderContents(context.cacheDir)
            context.externalCacheDir?.let { deleteFolderContents(it) }
        }
    }

    fun clearHistory() {
        repository.clearAllMessages()
    }

    private fun getFolderSize(file: File): Long {
        var size: Long = 0
        if (file.isDirectory) {
            file.listFiles()?.forEach { size += getFolderSize(it) }
        } else {
            size = file.length()
        }
        return size
    }

    private fun deleteFolderContents(file: File) {
        file.listFiles()?.forEach { 
            if (it.isDirectory) deleteFolderContents(it)
            it.delete()
        }
    }

    // Фабрика, потому что вьюмоделька не умеет сама прокидывать репозиторий в конструктор.
    // Стандартная андроидная магия.
    class Factory(private val repository: MSNPRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return MainViewModel(repository) as T
            }
            throw IllegalArgumentException("Опять какую-то левую вьюмодель подсунули")
        }
    }
}
