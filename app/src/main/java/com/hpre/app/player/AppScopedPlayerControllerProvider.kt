package com.hpre.app.player

internal class AppScopedPlayerControllerProvider<T : PlayerController>(
    private val factory: (initialPurpose: ConnectionPurpose) -> T
) {
    @Volatile
    private var instance: T? = null

    fun get(initialPurpose: ConnectionPurpose = ConnectionPurpose.NORMAL): T = instance ?: synchronized(this) {
        instance ?: factory(initialPurpose).also { instance = it }
    }

    fun getIfInitialized(): T? = instance
}
