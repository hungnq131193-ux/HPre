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

    fun record(throwable: Throwable) {
        _lastDetail.value = throwable::class.java.simpleName
    }
}
