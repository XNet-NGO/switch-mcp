package org.xnet.switchmcp

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
 * @param allowNonLoopbackTargets off by default (loopback-only), the §12 safety
 *   gate. When enabled, non-loopback targets are permitted (and the app must
 *   hold ACCESS_LOCAL_NETWORK for LAN targets, per C3 — enforced at the Android
 *   layer in [PortmuxController]).
 */
class NativePortmuxEngine(
    private val allowNonLoopbackTargets: Boolean = false,
) : PortmuxEngine {

    private val lock = ReentrantLock()
    private val routes = LinkedHashMap<String, Route>()
    private var handle: Long = 0L
    private var worker: Thread? = null

    override fun addRoute(
        proto: Proto,
        listenAddr: String,
        listenPort: Int,
        targetAddr: String,
        targetPort: Int,
    ): Route = lock.withLock {
        require(listenPort in 1..65535) { "listen port $listenPort out of range" }
        require(targetPort in 1..65535) { "target port $targetPort out of range" }
        val target = targetAddr.ifBlank { "127.0.0.1" }
        if (!allowNonLoopbackTargets && !isLoopback(target)) {
            throw IllegalArgumentException(
                "target '$target' is not loopback; enable ALLOW_ANY_TARGET to permit non-loopback targets",
            )
        }
        val route = Route(
            id = UUID.randomUUID().toString().take(8),
            proto = proto,
            listenAddr = listenAddr.ifBlank { "127.0.0.1" },
            listenPort = listenPort,
            targetAddr = target,
            targetPort = targetPort,
        )
        routes[route.id] = route
        if (worker != null) respawn()
        route
    }

    override fun removeRoute(id: String): Boolean = lock.withLock {
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

    private fun respawn() {
        stopLocked()
        val h = nativeNew()
        if (h == 0L) throw IllegalStateException("portmux_new failed")
        handle = h
        routes.values.forEach { r ->
            val proto = if (r.proto == Proto.TCP) 0 else 1
            val rc = nativeAddRoute(
                h, proto, r.listenAddr, r.listenPort,
                r.targetAddr, r.targetPort, EGRESS_NONE, "",
            )
            if (rc != 0) {
                nativeDestroy(h); handle = 0L
                throw IllegalStateException("add_route failed for ${r.summary()}")
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
    ): Int
    private external fun nativeRun(handle: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)

    companion object {
        private const val TAG = "switch-mcp"

        // Egress kinds mirrored from portmux.h (v0.1 uses NONE only).
        const val EGRESS_NONE = 0
        const val EGRESS_SOURCE = 1
        const val EGRESS_DEVICE = 2

        init {
            System.loadLibrary("switchmcp")
        }
    }
}
