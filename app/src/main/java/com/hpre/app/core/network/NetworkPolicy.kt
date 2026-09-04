package com.hpre.app.core.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.brotli.BrotliInterceptor
import java.util.concurrent.TimeUnit

internal class InMemoryCookieJar : CookieJar {
    private val lock = Any()
    private val cookies = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            this.cookies.removeAll { stored ->
                stored.expiresAt <= now || cookies.any { incoming ->
                    incoming.name == stored.name && incoming.domain == stored.domain && incoming.path == stored.path
                }
            }
            this.cookies += cookies.filter { it.expiresAt > now }
            if (this.cookies.size > MAX_COOKIES) {
                this.cookies.subList(0, this.cookies.size - MAX_COOKIES).clear()
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            cookies.removeAll { it.expiresAt <= now }
            cookies.filter { it.matches(url) }
        }
    }

    private companion object {
        const val MAX_COOKIES = 256
    }
}

data class NetworkPolicy(
    val connectTimeoutSeconds: Long = 15,
    val readTimeoutSeconds: Long = 20,
    val callTimeoutSeconds: Long = 30,
    val maxIdleConnections: Int = 16,
    val keepAliveDurationMinutes: Long = 5
) {
    fun createOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(maxIdleConnections, keepAliveDurationMinutes, TimeUnit.MINUTES))
            .cookieJar(InMemoryCookieJar())
            .addInterceptor(BrotliInterceptor)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    companion object {
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    }
}
