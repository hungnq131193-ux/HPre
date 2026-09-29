package com.hpre.app.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A discovered FCast receiver on the local network. */
data class FCastDevice(
    val name: String,
    val host: String,
    val port: Int
)

/**
 * mDNS discovery for `_fcast._tcp` receivers using the platform NsdManager — no extra dependency.
 * Discovery only runs while [start]ed.
 */
class FCastDiscovery(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val found = LinkedHashMap<String, FCastDevice>()

    private val _devices = MutableStateFlow<List<FCastDevice>>(emptyList())
    val devices: StateFlow<List<FCastDevice>> = _devices.asStateFlow()

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = stop()
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            synchronized(found) { found.remove(serviceInfo.serviceName) }
            publish()
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: return
                    synchronized(found) {
                        found[info.serviceName] = FCastDevice(info.serviceName, host, info.port)
                    }
                    publish()
                }
            })
        }
    }

    private var running = false

    fun start() {
        if (running) return
        running = true
        runCatching {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { nsd.stopServiceDiscovery(discoveryListener) }
    }

    private fun publish() {
        _devices.value = synchronized(found) { found.values.toList() }
    }

    companion object {
        const val SERVICE_TYPE = "_fcast._tcp"
    }
}
