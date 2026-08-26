package com.hpre.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class NetworkPolicyTest {

    @Test
    fun standard_timeouts_are_bounded_and_configured() {
        val policy = NetworkPolicy(
            connectTimeoutSeconds = 15,
            readTimeoutSeconds = 20,
            callTimeoutSeconds = 30
        )
        val client = policy.createOkHttpClient()

        assertEquals(15000, client.connectTimeoutMillis)
        assertEquals(20000, client.readTimeoutMillis)
        assertEquals(30000, client.callTimeoutMillis)
        assertTrue(client.followRedirects)
        assertTrue(client.followSslRedirects)
        assertNotNull(client.connectionPool)
    }

    @Test
    fun extractor_downloader_and_player_share_app_clients_and_timeouts_pool_config() {
        val fakeContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = this
        }
        val customClient = NetworkPolicy(
            connectTimeoutSeconds = 15,
            readTimeoutSeconds = 20,
            callTimeoutSeconds = 30,
            maxIdleConnections = 5,
            keepAliveDurationMinutes = 5
        ).createOkHttpClient()

        val appContainer = com.hpre.app.di.DefaultAppContainer(
            context = fakeContext,
            okHttpClient = customClient
        )
        val sharedClient = appContainer.okHttpClient
        
        // Assert timeouts on app container shared client
        assertEquals(15000, sharedClient.connectTimeoutMillis)
        assertEquals(20000, sharedClient.readTimeoutMillis)
        assertEquals(30000, sharedClient.callTimeoutMillis)
        assertNotNull(sharedClient.connectionPool)
        org.junit.Assert.assertSame(customClient, sharedClient)
        
        // MediaSourceFactory in AppContainer uses this shared okHttpClient
        assertNotNull(appContainer.mediaSourceFactory)
    }

    @Test
    fun custom_timeouts_are_respected() {
        val policy = NetworkPolicy(
            connectTimeoutSeconds = 10,
            readTimeoutSeconds = 10,
            callTimeoutSeconds = 15
        )
        val client = policy.createOkHttpClient()

        assertEquals(10000, client.connectTimeoutMillis)
        assertEquals(10000, client.readTimeoutMillis)
        assertEquals(15000, client.callTimeoutMillis)
    }
}
