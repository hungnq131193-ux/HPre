package com.hpre.app.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class AppSettingsSnapshot internal constructor(
    source: Flow<AppSettings>,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher
) {
    private val state = MutableStateFlow(AppSettings())
    private val loaded = CompletableDeferred<Unit>()

    val settings: StateFlow<AppSettings> = state.asStateFlow()
    val value: AppSettings get() = state.value

    init {
        scope.launch(dispatcher) {
            source.catch { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                emit(AppSettings())
            }.collect {
                state.value = it
                if (!loaded.isCompleted) loaded.complete(Unit)
            }
        }.invokeOnCompletion { cause ->
            if (cause == null) loaded.complete(Unit) else loaded.completeExceptionally(cause)
        }
    }

    suspend fun awaitValue(): AppSettings {
        loaded.await()
        return state.value
    }
}

fun Flow<AppSettings>.shareAppSettings(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
): AppSettingsSnapshot = AppSettingsSnapshot(this, scope, dispatcher)
