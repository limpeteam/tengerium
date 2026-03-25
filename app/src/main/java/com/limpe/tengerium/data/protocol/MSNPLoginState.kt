package com.limpe.tengerium.data.protocol

/**
 * Состояния процесса авторизации.
 */
sealed class MSNPLoginState {
    object Idle : MSNPLoginState()
    object Loading : MSNPLoginState()
    object Reconnecting : MSNPLoginState()
    data class Success(val account: String, val nickname: String) : MSNPLoginState()
    data class Error(val message: String) : MSNPLoginState()
}
