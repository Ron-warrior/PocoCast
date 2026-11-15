package com.pococast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Finds Apple TVs on the local network by browsing the _airplay._tcp mDNS
 * service with Android's NsdManager.
 */
class AirPlayDiscovery(private val context: Context) {

    data class Device(val name: String, val host: String, val port: Int)

    private var manager: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val resolver = Executors.newSingleThreadExecutor()
    private val seen = HashSet<String>()

    fun start(onDevice: (Device) -> Unit, onFinished: (String?) -> Unit) {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            onFinished("mDNS discovery is not available on this device. Enter the Apple TV's IP address instead.")
            return
        }
        manager = nsd
        seen.clear()

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!seen.add(serviceInfo.serviceName)) return
                try {
                    resolver.execute {
                        val resolved = resolve(nsd, serviceInfo)
                        if (resolved == null) {
                            seen.remove(serviceInfo.serviceName)
                            return@execute
                        }
                        val host = hostOf(resolved)
                        if (host == null) {
                            seen.remove(serviceInfo.serviceName)
                            return@execute
                        }
                        val port = if (resolved.port > 0) resolved.port else 7000
                        onDevice(Device(resolved.serviceName, host, port))
                    }
                } catch (ignored: RejectedExecutionException) {
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onFinished("Could not start mDNS discovery (error $errorCode). Enter the Apple TV's IP address instead.")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = discovery

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (error: Exception) {
            onFinished("Discovery failed: ${error.message}")
        }
    }

    fun stop() {
        val nsd = manager
        val active = listener
        listener = null
        if (nsd != null && active != null) {
            try {
                nsd.stopServiceDiscovery(active)
            } catch (ignored: Exception) {
            }
        }
        resolver.shutdownNow()
    }

    private fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? {
        val latch = CountDownLatch(1)
        var result: NsdServiceInfo? = null
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    latch.countDown()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    result = serviceInfo
                    latch.countDown()
                }
            })
            latch.await(6, TimeUnit.SECONDS)
        } catch (ignored: Exception) {
            return null
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): String? = try {
        if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.firstOrNull()?.hostAddress
        } else {
            info.host?.hostAddress
        }
    } catch (ignored: Exception) {
        null
    }

    private companion object {
        const val SERVICE_TYPE = "_airplay._tcp"
    }
}
