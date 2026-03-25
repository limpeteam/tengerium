package com.limpe.tengerium.data.protocol

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import uniffi.msnp11_sdk.*

/**
 * Безопасное уничтожение объектов Rust SDK, реализующих Disposable.
 */
fun Any?.destroySafe() {
    if (this is Disposable) {
        try {
            this.destroy()
        } catch (e: Exception) {
            Log.w("ProtocolSafe", "Error destroying object: ${e.message}")
        }
    }
}

/**
 * Запуск задачи с автоматическим логированием ошибок и игнорированием CancellationException.
 */
fun CoroutineScope.safeLaunch(
    tag: String = "ProtocolTask",
    onError: ((Throwable) -> Unit)? = null,
    block: suspend CoroutineScope.() -> Unit
) = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        val message = if (e.message.isNullOrBlank()) e.javaClass.simpleName else e.message
        
        // TransmittingException и другие ожидаемые ошибки UniFFI логируем как Warning, 
        // чтобы не засорять Error-логи некритичными сбоями (например, контакт уже добавлен).
        if (e.javaClass.name.contains("TransmittingException") || 
            e is ContactException.ContactIsOffline || 
            e is MessagingException.MessageNotDelivered) {
            Log.w(tag, "Background task warning: $message")
        } else {
            Log.e(tag, "Background task failed: $message", e)
        }
        onError?.invoke(e)
    }
}
