package org.xnet.switchmcp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Requests specific transports via [ConnectivityManager.requestNetwork] and
 * keeps the requests alive so the app is permitted to pin sockets to those
 * networks (plain enumeration + bindSocket is not enough when the network isn't
 * the active default — the framework requires the app to have requested it).
 *
 * Ported from Switchboard. One instance per engine run. Call [resolveHandle]
 * for each NETWORK-egress route before adding it, then [releaseAll] on stop.
 * [onHandleChanged] feeds the native live-handle refresh so a long-lived SOCKS
 * proxy survives a Wi-Fi<->cellular handoff instead of latching on a stale
 * handle (fails closed on loss).
 */
class NetworkRequester(context: Context) {

    private val cm =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    private class Holder {
        val latch = CountDownLatch(1)
        @Volatile var network: Network? = null
        lateinit var callback: ConnectivityManager.NetworkCallback
    }

    private val holders = ConcurrentHashMap<NetTransport, Holder>()

    /**
     * Notified whenever a requested transport's live [Network] changes: a
     * non-zero handle on onAvailable, 0 on onLost (currently unavailable).
     * Set before [resolveHandle] so the initial onAvailable is observed.
     */
    @Volatile
    var onHandleChanged: ((transport: NetTransport, handle: Long) -> Unit)? = null

    /**
     * Ensure a network of [transport] is requested and resolved; returns its
     * network handle, or [fallbackHandle] if it could not be obtained within
     * [timeoutMs]. For [NetTransport.ANY], returns [fallbackHandle] unchanged.
     */
    fun resolveHandle(
        transport: NetTransport,
        fallbackHandle: Long = 0L,
        timeoutMs: Long = 8000,
    ): Long {
        if (transport == NetTransport.ANY) return fallbackHandle

        val holder = holders.getOrPut(transport) { request(transport) }
        val ok = try {
            holder.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        val net = holder.network
        return if (ok && net != null) net.networkHandle else {
            Log.w(TAG, "network for $transport not available; falling back")
            fallbackHandle
        }
    }

    private fun request(transport: NetTransport): Holder {
        val holder = Holder()
        val transportType = when (transport) {
            NetTransport.WIFI -> NetworkCapabilities.TRANSPORT_WIFI
            NetTransport.CELLULAR -> NetworkCapabilities.TRANSPORT_CELLULAR
            NetTransport.ETHERNET -> NetworkCapabilities.TRANSPORT_ETHERNET
            NetTransport.ANY -> -1
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .apply { if (transportType >= 0) addTransportType(transportType) }
            .build()

        holder.callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                holder.network = network
                holder.latch.countDown()
                Log.i(TAG, "network available for $transport: ${network.networkHandle}")
                onHandleChanged?.invoke(transport, network.networkHandle)
            }

            override fun onLost(network: Network) {
                if (holder.network == network) holder.network = null
                Log.i(TAG, "network lost for $transport")
                if (holder.network == null) onHandleChanged?.invoke(transport, 0L)
            }
        }
        try {
            cm.requestNetwork(req, holder.callback)
        } catch (t: Throwable) {
            // e.g. SecurityException without CHANGE_NETWORK_STATE
            Log.w(TAG, "requestNetwork($transport) failed: ${t.message}")
            holder.latch.countDown()
        }
        return holder
    }

    /** Release all held network requests. Safe to call once, on stop. */
    fun releaseAll() {
        holders.values.forEach { h ->
            try {
                cm.unregisterNetworkCallback(h.callback)
            } catch (_: Throwable) {
            }
        }
        holders.clear()
    }

    private companion object {
        const val TAG = "switch-mcp"
    }
}
