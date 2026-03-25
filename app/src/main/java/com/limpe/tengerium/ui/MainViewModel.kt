package com.limpe.tengerium.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.limpe.tengerium.data.MSNPRepository
import com.limpe.tengerium.data.db.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

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
