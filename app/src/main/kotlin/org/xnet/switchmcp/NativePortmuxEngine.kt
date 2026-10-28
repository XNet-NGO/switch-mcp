package org.xnet.switchmcp

import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * [PortmuxEngine] that embeds the portmux engine in-process via JNI
 * (libswitchmcp.so, packaged in the APK). No separate portmux install.
 *
 * portmux fixes routes at run() and its scheduler is thread-local, so a change
 * while running tears down the native instance and re-runs on a fresh worker
 * thread with the updated route set.
 *
 * Egress: NONE (default/loopback), SOURCE (bind local IP), and NETWORK (no-root
 * pin to a Wi-Fi/cellular/ethernet Android Network via android_setsocknetwork).
 * NETWORK routes request the transport via [NetworkRequester] and push live
 * handle updates into the native CALLBACK context, so a long-lived SOCKS proxy
 * survives a network handoff (and fails closed when the network is gone).
 *
 * @param context application context (for NetworkRequester). May be null in
 *   tests that only use NONE/SOURCE egress.
 * @param allowNonLoopbackTargets off by default (loopback-only), the §12 gate.
 */
class NativePortmuxEngine(
    private val context: Context? = null,
    private val allowNonLoopbackTargets: Boolean = false,
) : PortmuxEngine {

    private val lock = ReentrantLock()
    private val routes = LinkedHashMap<String, Route>()
    private var handle: Long = 0L
    private var worker: Thread? = null
    private var requester: NetworkRequester? = null

    // Tunnel secrets kept out of the Route (which is returned to callers/logged):
    // the PSK (64 hex) and cipher per tunnel route id.
    private val tunnelPsk = HashMap<String, String>()
    private val tunnelCipher = HashMap<String, Int>()

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    override fun addRoute(
        proto: Proto,
        listenAddr: String,
        listenPort: Int,
        targetAddr: String,
        targetPort: Int,
        egress: EgressMode,
        egressArg: String,
        transport: NetTransport,
    ): Route = lock.withLock {
        require(listenPort in 1..65535) { "listen port $listenPort out of range" }
        require(targetPort in 1..65535) { "target port $targetPort out of range" }
        val target = targetAddr.ifBlank { "127.0.0.1" }
        if (!allowNonLoopbackTargets && !isLoopback(target)) {
            throw IllegalArgumentException(
                "target '$target' is not loopback; enable ALLOW_ANY_TARGET to permit non-loopback targets",
            )
        }
        validateEgress(egress, egressArg)
        val route = Route(
            id = UUID.randomUUID().toString().take(8),
            proto = proto,
            listenAddr = listenAddr.ifBlank { "127.0.0.1" },
            listenPort = listenPort,
            targetAddr = target,
            targetPort = targetPort,
            egress = egress,
            egressArg = egressArg,
            transport = transport,
        )
        routes[route.id] = route
        if (worker != null) respawn()
        route
    }

    override fun addSocks(
        listenAddr: String,
        listenPort: Int,
        egress: EgressMode,
        egressArg: String,
        transport: NetTransport,
    ): Route = lock.withLock {
        require(listenPort in 1..65535) { "listen port $listenPort out of range" }
        validateEgress(egress, egressArg)
        val route = Route(
            id = UUID.randomUUID().toString().take(8),
            proto = Proto.TCP,
            listenAddr = listenAddr.ifBlank { "127.0.0.1" },
            listenPort = listenPort,
            targetAddr = "",
            targetPort = 0,
            isSocks = true,
            egress = egress,
            egressArg = egressArg,
            transport = transport,
        )
        routes[route.id] = route
        if (worker != null) respawn()
        route
    }

    override fun addTunnel(
        serverAddr: String,
        serverPort: Int,
        targetAddr: String,
        targetPort: Int,
        pskHex: String,
        cipher: Int,
        egress: EgressMode,
        egressArg: String,
        transport: NetTransport,
        revListenAddr: String,
        revListenPort: Int,
        revTargetAddr: String,
        revTargetPort: Int,
        revProto: Int,
        jumpHops: List<String>,
        jumpTargetAddr: String,
        jumpTargetPort: Int,
        jumpProto: Int,
    ): Route = lock.withLock {
        require(serverPort in 1..65535) { "server port $serverPort out of range" }
        require(targetPort in 1..65535) { "target port $targetPort out of range" }
        require(pskHex.length == 64 && pskHex.all { it.isHexDigit() }) {
            "psk must be 64 hex chars (32 bytes)"
        }
        require(serverAddr.isNotBlank()) { "server address required" }
        if (revListenPort > 0) {
            require(revListenPort in 1..65535) { "reverse listen port $revListenPort out of range" }
            require(revTargetPort in 1..65535) { "reverse target port $revTargetPort out of range" }
            require(revProto in 0..2) { "reverse proto must be 0(TCP), 1(UDP), or 2(BOTH)" }
            // Reverse target is a server-side connect; gate non-loopback behind allowAnyTarget
            val rtarget = revTargetAddr.ifBlank { "127.0.0.1" }
            if (!allowNonLoopbackTargets && !isLoopback(rtarget)) {
                throw IllegalArgumentException(
                    "reverse target '$rtarget' is not loopback; enable ALLOW_ANY_TARGET to permit non-loopback reverse targets",
                )
            }
        }
        if (jumpHops.isNotEmpty()) {
            require(jumpHops.size in 1..PM_MUX_MAX_HOPS) {
                "jump hops count ${jumpHops.size} out of range (1..$PM_MUX_MAX_HOPS)"
            }
            require(jumpProto in 0..1) { "jump proto must be 0(TCP) or 1(UDP)" }
            require(jumpTargetPort in 1..65535) { "jump target port $jumpTargetPort out of range" }
            // A jump chain requires a reverse listener as the local plaintext
            // entry that emits OPENJ (per the v0.5.0 contract).
            require(revListenPort > 0) {
                "jump routing requires a reverse listener (revListenPort>0) as the local OPENJ entry"
            }
            // hops[0] must equal the carrier dest (serverAddr:serverPort).
            val first = jumpHops.first()
            require(first == "$serverAddr:$serverPort") {
                "jumpHops[0] ('$first') must equal the carrier dest '$serverAddr:$serverPort'"
            }
            // Final jump target is a terminal connect; gate non-loopback.
            val jtarget = jumpTargetAddr.ifBlank { "127.0.0.1" }
            if (!allowNonLoopbackTargets && !isLoopback(jtarget)) {
                throw IllegalArgumentException(
                    "jump target '$jtarget' is not loopback; enable ALLOW_ANY_TARGET to permit non-loopback jump targets",
                )
            }
        }
        validateEgress(egress, egressArg)
        val route = Route(
            id = UUID.randomUUID().toString().take(8),
            proto = Proto.TCP,
            listenAddr = "",
            listenPort = 0,
            targetAddr = targetAddr.ifBlank { "127.0.0.1" },
            targetPort = targetPort,
            isTunnel = true,
            serverAddr = serverAddr,
            serverPort = serverPort,
            egress = egress,
            egressArg = egressArg,
            transport = transport,
            revListenAddr = revListenAddr,
            revListenPort = revListenPort,
            revTargetAddr = revTargetAddr,
            revTargetPort = revTargetPort,
            revProto = revProto,
            jumpHops = jumpHops,
            jumpTargetAddr = jumpTargetAddr.ifBlank { "127.0.0.1" },
            jumpTargetPort = jumpTargetPort,
            jumpProto = jumpProto,
        )
        tunnelPsk[route.id] = pskHex
        tunnelCipher[route.id] = cipher
        routes[route.id] = route
        if (worker != null) respawn()
        route
    }

    override fun addPeer(
        listenAddr: String,
        listenPort: Int,
        pskHex: String,
        cipher: Int,
        egress: EgressMode,
        egressArg: String,
        transport: NetTransport,
    ): Route = lock.withLock {
        require(listenPort in 1..65535) { "listen port $listenPort out of range" }
        require(pskHex.length == 64 && pskHex.all { it.isHexDigit() }) {
            "psk must be 64 hex chars (32 bytes)"
        }
        validateEgress(egress, egressArg)
        val route = Route(
            id = UUID.randomUUID().toString().take(8),
            proto = Proto.TCP,
            listenAddr = listenAddr.ifBlank { "127.0.0.1" },
            listenPort = listenPort,
            targetAddr = "",
            targetPort = 0,
            isPeer = true,
            egress = egress,
            egressArg = egressArg,
            transport = transport,
        )
        tunnelPsk[route.id] = pskHex
        tunnelCipher[route.id] = cipher
        routes[route.id] = route
        if (worker != null) respawn()
        route
    }

    override fun removeRoute(id: String): Boolean = lock.withLock {
        tunnelPsk.remove(id); tunnelCipher.remove(id)
        val removed = routes.remove(id) != null
        if (removed && worker != null) {
            if (routes.isEmpty()) stopLocked() else respawn()
        }
        removed
    }

    override fun listRoutes(): List<Route> = lock.withLock { routes.values.toList() }

    override fun isRunning(): Boolean = lock.withLock { worker?.isAlive == true }

    override fun start() {
        lock.withLock {
            if (worker?.isAlive == true) return
            if (routes.isEmpty()) throw IllegalStateException("no routes to start")
            respawn()
        }
    }

    override fun stop() {
        lock.withLock { stopLocked() }
    }

    // --- internals (lock held) ---

    private fun validateEgress(egress: EgressMode, egressArg: String) {
        if (egress == EgressMode.SOURCE && egressArg.isBlank()) {
            throw IllegalArgumentException("SOURCE egress requires a source IP (egressArg)")
        }
        if (egress == EgressMode.NETWORK && context == null) {
            throw IllegalStateException("NETWORK egress requires a Context")
        }
    }

    private fun egressKind(egress: EgressMode): Int = when (egress) {
        EgressMode.NONE -> EGRESS_NONE
        EgressMode.SOURCE -> EGRESS_SOURCE
        EgressMode.NETWORK -> EGRESS_CALLBACK
    }

    /** Token grouping CALLBACK routes by transport; > 0 means "pinned". */
    private fun tokenFor(transport: NetTransport): Int = transport.ordinal + 1

    private fun respawn() {
        stopLocked()
        val h = nativeNew()
        if (h == 0L) throw IllegalStateException("portmux_new failed")
        handle = h

        // Set up (or reuse) the network requester and wire live handle updates
        // into the native CALLBACK contexts before adding NETWORK routes.
        val needsNetwork = routes.values.any { it.egress == EgressMode.NETWORK }
        if (needsNetwork && requester == null) {
            val ctx = context ?: throw IllegalStateException("NETWORK egress requires a Context")
            requester = NetworkRequester(ctx).also { r ->
                r.onHandleChanged = { transport, handleValue ->
                    nativeUpdateNetworkHandle(tokenFor(transport), handleValue)
                }
            }
        }

        routes.values.forEach { r ->
            val kind = egressKind(r.egress)
            val netHandle: Long
            val token: Int
            if (r.egress == EgressMode.NETWORK) {
                netHandle = requester!!.resolveHandle(r.transport)
                token = tokenFor(r.transport)
            } else {
                netHandle = 0L
                token = 0
            }
            val rc = if (r.isTunnel) {
                val crc = nativeAddTunnelClient(
                    h, r.serverAddr, r.serverPort, r.targetAddr, r.targetPort,
                    tunnelPsk[r.id] ?: "", tunnelCipher[r.id] ?: 0,
                    kind, r.egressArg, netHandle, token,
                    r.revListenAddr, r.revListenPort, r.revTargetAddr, r.revTargetPort, r.revProto,
                )
                // v0.5.0: configure the edge jump-list immediately after the
                // edge client is added (set_jumps targets the most-recent route).
                if (crc == 0 && r.jumpHops.isNotEmpty()) {
                    val hopsAddrCsv = r.jumpHops.joinToString(",") { it.substringBeforeLast(':') }
                    val hopsPortCsv = r.jumpHops.joinToString(",") { it.substringAfterLast(':') }
                    nativeSetJumps(h, hopsAddrCsv, hopsPortCsv, r.jumpTargetAddr, r.jumpTargetPort, r.jumpProto)
                } else {
                    crc
                }
            } else if (r.isPeer) {
                nativeAddTunnelPeer(
                    h, r.listenAddr, r.listenPort,
                    tunnelPsk[r.id] ?: "", tunnelCipher[r.id] ?: 0,
                    kind, r.egressArg, netHandle, token,
                )
            } else if (r.isSocks) {
                nativeAddSocks(h, r.listenAddr, r.listenPort, kind, r.egressArg, netHandle, token)
            } else {
                val proto = if (r.proto == Proto.TCP) 0 else 1
                nativeAddRoute(
                    h, proto, r.listenAddr, r.listenPort,
                    r.targetAddr, r.targetPort, kind, r.egressArg, netHandle, token,
                )
            }
            if (rc != 0) {
                nativeDestroy(h); handle = 0L
                throw IllegalStateException("add failed for ${r.summary()}")
            }
        }
        val t = thread(name = "portmux-run", isDaemon = true) {
            nativeRun(h) // blocks until stop
            Log.i(TAG, "portmux-run thread exited")
        }
        worker = t
        Log.i(TAG, "engine running with ${routes.size} route(s)")
    }

    private fun stopLocked() {
        val h = handle
        val t = worker
        if (h != 0L) {
            nativeStop(h)
            t?.join(3000)
            nativeDestroy(h)
            handle = 0L
        }
        worker = null
        requester?.releaseAll()
        requester = null
    }

    private fun isLoopback(addr: String): Boolean =
        addr.isBlank() || addr == "127.0.0.1" || addr == "::1" || addr == "localhost"

    // JNI instance methods. Symbols:
    // Java_org_xnet_switchmcp_NativePortmuxEngine_native*
    private external fun nativeNew(): Long
    private external fun nativeAddRoute(
        handle: Long, proto: Int,
        listenAddr: String, listenPort: Int,
        targetAddr: String, targetPort: Int,
        egressKind: Int, egressArg: String,
        networkHandle: Long, token: Int,
    ): Int
    private external fun nativeAddSocks(
        handle: Long,
        listenAddr: String, listenPort: Int,
        egressKind: Int, egressArg: String,
        networkHandle: Long, token: Int,
    ): Int
    private external fun nativeAddTunnelClient(
        handle: Long,
        serverAddr: String, serverPort: Int,
        targetAddr: String, targetPort: Int,
        pskHex: String, cipher: Int,
        egressKind: Int, egressArg: String,
        networkHandle: Long, token: Int,
        rlistenAddr: String, rlistenPort: Int,
        rtargetAddr: String, rtargetPort: Int, rproto: Int,
    ): Int
    private external fun nativeAddTunnelPeer(
        handle: Long,
        listenAddr: String, listenPort: Int,
        pskHex: String, cipher: Int,
        egressKind: Int, egressArg: String,
        networkHandle: Long, token: Int,
    ): Int
    private external fun nativeSetJumps(
        handle: Long,
        hopsAddrCsv: String, hopsPortCsv: String,
        targetAddr: String, targetPort: Int, proto: Int,
    ): Int
    private external fun nativeUpdateNetworkHandle(token: Int, netHandle: Long)
    private external fun nativeRun(handle: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)

    companion object {
        private const val TAG = "switch-mcp"

        // Egress kinds mirrored from portmux.h.
        const val EGRESS_NONE = 0
        const val EGRESS_SOURCE = 1
        const val EGRESS_DEVICE = 2
        const val EGRESS_CALLBACK = 3

        // Cap on jump-list length, mirrored from portmux-tunnel.h (v0.5.0).
        const val PM_MUX_MAX_HOPS = 8

        init {
            System.loadLibrary("switchmcp")
        }
    }
}
