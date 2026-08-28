package com.hpre.app.player

internal class AppScopedPlayerControllerProvider<T : PlayerController>(
    private val factory: () -> T
) {
    @Volatile
    private var instance: T? = null

    fun get(): T = instance ?: synchronized(this) {
        instance ?: factory().also { instance = it }
    }

    fun getIfInitialized(): T? = instance
}
