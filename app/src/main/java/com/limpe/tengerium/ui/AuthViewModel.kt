package com.limpe.tengerium.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.limpe.tengerium.data.MSNPRepository

/**
 * ViewModel для экрана авторизации и OOBE.
 */
class AuthViewModel(application: Application, private val repository: MSNPRepository) : AndroidViewModel(application) {

    val loginState = repository.loginState

    fun getSavedAccount(): String? = repository.getSavedAccount()
    fun getSavedPassword(): String? = repository.getSavedPassword()

    fun login(account: String, pass: String, rememberMe: Boolean = true) {
        repository.login(account, pass, rememberMe)
    }

    fun logout() {
        repository.logout()
    }

    fun updateAvatar(filePath: String) {
        repository.updateAvatar(filePath)
    }

    fun updateNickname(nickname: String) {
        repository.updateNickname(nickname)
    }

    class Factory(private val repository: MSNPRepository, private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return AuthViewModel(application, repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
