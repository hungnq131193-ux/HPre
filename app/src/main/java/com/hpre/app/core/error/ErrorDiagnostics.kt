package com.hpre.app.core.error

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Retains the class name of the last unclassified failure so an error screen can
 * show a one-line technical hint under the generic "unexpected error" message.
 * Only exception class names are recorded — raw messages can carry URLs or tokens.
 */
object ErrorDiagnostics {
    private val _lastDetail = MutableStateFlow<String?>(null)
    val lastDetail: StateFlow<String?> = _lastDetail

    private val urlPattern = Regex("https?://\\S+")

    fun record(throwable: Throwable) {
        val name = throwable::class.java.simpleName
        // LinkageError messages are JVM signatures ("No virtual method X(L..;)V in class Y"),
        // safe to show verbatim — they name the exact missing API. Other throwables stay
        // class-name-only since arbitrary messages can embed URLs or tokens.
        _lastDetail.value = if (throwable is LinkageError) {
            val signature = throwable.message?.lineSequence()?.firstOrNull()
                ?.replace(urlPattern, "[url]")
                ?.take(180)
                ?: ""
            if (signature.isEmpty()) name else "$name: $signature"
        } else {
            name
        }
    }
}
