package com.flowtube.app.core.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class NetworkPolicy(
    val connectTimeoutSeconds: Long = 15,
    val readTimeoutSeconds: Long = 20,
    val callTimeoutSeconds: Long = 30,
    val maxIdleConnections: Int = 5,
    val keepAliveDurationMinutes: Long = 5
) {
    fun createOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(maxIdleConnections, keepAliveDurationMinutes, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
