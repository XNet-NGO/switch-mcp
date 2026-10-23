package org.xnet.switchmcp

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * The single source of truth for routes and engine state (SPEC §3). Both the
 * MCP tool layer and a future AppFunctions front end (§8) call this class, so
 * all policy (loopback-only default, ACCESS_LOCAL_NETWORK gating for LAN
 * targets per C3) lives here rather than in the HTTP layer.
 *
 * @param allowAnyTarget the `ALLOW_ANY_TARGET` flag (§4 safety defaults). When
 *   false (default), only loopback targets are accepted. When true, non-loopback
 *   targets are allowed but LAN targets additionally require
 *   ACCESS_LOCAL_NETWORK to be granted at runtime (C3).
 */
class PortmuxController(
    private val context: Context,
    val allowAnyTarget: Boolean = false,
) {
    private val engine: PortmuxEngine = NativePortmuxEngine(allowNonLoopbackTargets = allowAnyTarget)
    private val networkGuard = NetworkGuard(context)
    val role: DeploymentRole = DeploymentRole.detect(context)

    data class Status(
        val running: Boolean,
        val routeCount: Int,
        val network: NetworkGuard.State,
        val role: DeploymentRole,
        val allowAnyTarget: Boolean,
    ) {
        fun describe(): String = buildString {
            append(if (running) "running" else "stopped")
            append("; ").append(routeCount).append(" route(s)")
            append("; ").append(network.describe())
            append("; role=").append(role.name.lowercase())
            append("; allowAnyTarget=").append(allowAnyTarget)
        }
    }

    fun addRoute(
        proto: Proto,
        listenAddr: String,
        listenPort: Int,
        targetAddr: String,
        targetPort: Int,
    ): Route {
        val target = targetAddr.ifBlank { "127.0.0.1" }
        if (!isLoopback(target)) {
            if (!allowAnyTarget) {
                throw IllegalArgumentException(
                    "non-loopback target '$target' rejected; set ALLOW_ANY_TARGET to permit it",
                )
            }
            if (!hasLocalNetworkPermission()) {
                throw IllegalStateException(
                    "non-loopback target '$target' requires ACCESS_LOCAL_NETWORK, which is not granted",
                )
            }
        }
        return engine.addRoute(proto, listenAddr, listenPort, target, targetPort)
    }

    /**
     * Add a SOCKS5 proxy listener. A SOCKS proxy dials arbitrary destinations
     * supplied per-connection by the client, which is equivalent to permitting
     * arbitrary (including non-loopback) egress. It is therefore gated behind
     * [allowAnyTarget] and MUST NOT be enabled otherwise.
     */
    fun addSocks(
        listenAddr: String,
        listenPort: Int,
    ): Route {
        if (!allowAnyTarget) {
            throw IllegalStateException(
                "SOCKS5 proxy implies arbitrary per-connection egress; set " +
                    "ALLOW_ANY_TARGET to enable it",
            )
        }
        return engine.addSocks(listenAddr, listenPort)
    }

    fun removeRoute(id: String): Boolean = engine.removeRoute(id)

    fun listRoutes(): List<Route> = engine.listRoutes()

    fun isRunning(): Boolean = engine.isRunning()

    fun start() = engine.start()

    fun stop() = engine.stop()

    fun status(): Status = Status(
        running = engine.isRunning(),
        routeCount = engine.listRoutes().size,
        network = networkGuard.state(),
        role = role,
        allowAnyTarget = allowAnyTarget,
    )

    fun networkState(): NetworkGuard.State = networkGuard.state()

    private fun isLoopback(addr: String): Boolean =
        addr.isBlank() || addr == "127.0.0.1" || addr == "::1" || addr == "localhost"

    private fun hasLocalNetworkPermission(): Boolean {
        // ACCESS_LOCAL_NETWORK is API 36+. Guard the string so older SDKs still
        // compile/run; on devices without it, treat as not granted.
        val perm = "android.permission.ACCESS_LOCAL_NETWORK"
        return try {
            context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            Log.w("switch-mcp", "ACCESS_LOCAL_NETWORK check failed: ${t.message}")
            false
        }
    }
}
