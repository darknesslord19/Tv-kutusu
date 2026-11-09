package com.tvkutu.app.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

/**
 * Aynı Wi‑Fi'deki TvKutu'ları bulur (mDNS / NSD) ve bu cihazı ağda duyurur.
 * Bulunan cihazlar [onChange] ile ana iş parçacığında bildirilir.
 */
class SyncDiscovery(
    context: Context,
    private val myId: String,
    private val onChange: (List<Peer>) -> Unit,
) {
    data class Peer(val deviceId: String, val name: String, val host: String, val port: Int)

    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val peers = LinkedHashMap<String, Peer>() // servis adı → cihaz
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null

    fun start(port: Int, name: String) {
        stop()
        runCatching {
            lock = (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("tvkutu-sync")?.apply { setReferenceCounted(false); acquire() }
        }
        val info = NsdServiceInfo().apply {
            serviceName = "TvKutu-" + myId.take(8)
            serviceType = TYPE
            setPort(port)
            setAttribute("id", myId)
            setAttribute("name", name.take(40))
        }
        val rl = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        regListener = rl
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, rl) }

        val dl = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                if (i.serviceName == "TvKutu-" + myId.take(8)) return
                main.post { resolveQueue.add(i); resolveNext() }
            }
            override fun onServiceLost(i: NsdServiceInfo) {
                main.post { if (peers.remove(i.serviceName) != null) onChange(peers.values.toList()) }
            }
        }
        discListener = dl
        runCatching { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, dl) }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val next = resolveQueue.poll() ?: return
        resolving = true
        runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) {
                    main.post { resolving = false; resolveNext() }
                }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val id = i.attributes["id"]?.toString(Charsets.UTF_8).orEmpty()
                    val name = i.attributes["name"]?.toString(Charsets.UTF_8)?.ifBlank { null } ?: i.serviceName
                    val host = i.host?.hostAddress.orEmpty()
                    main.post {
                        if (id.isNotEmpty() && id != myId && host.isNotEmpty()) {
                            peers[i.serviceName] = Peer(id, name, host, i.port)
                            onChange(peers.values.toList())
                        }
                        resolving = false
                        resolveNext()
                    }
                }
            })
        }.onFailure { resolving = false }
    }

    fun stop() {
        regListener?.let { runCatching { nsd.unregisterService(it) } }
        discListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        regListener = null
        discListener = null
        runCatching { lock?.release() }
        lock = null
        resolveQueue.clear()
        resolving = false
    }

    companion object {
        const val TYPE = "_tvkutu._tcp."
    }
}
