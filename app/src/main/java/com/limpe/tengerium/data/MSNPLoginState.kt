package com.limpe.tengerium.data

/**
 * Состояния процесса авторизации.
 */
sealed class MSNPLoginState {
    object Idle : MSNPLoginState()
    object Loading : MSNPLoginState()
    object NoInternet : MSNPLoginState()
    data class Reconnecting(val secondsRemaining: Int) : MSNPLoginState()
    data class Success(val account: String, val nickname: String, val avatarUrl: String? = null) : MSNPLoginState()
    data class Error(val message: String) : MSNPLoginState()
    data class LoggedInElsewhere(val message: String) : MSNPLoginState()
}
