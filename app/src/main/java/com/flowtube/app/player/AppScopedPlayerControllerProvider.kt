package com.flowtube.app.player

internal class AppScopedPlayerControllerProvider<T : PlayerController>(
    private val factory: () -> T
) {
    private val instance: T by lazy(factory)

    fun get(): T = instance
}
