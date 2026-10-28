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
    private val engine: PortmuxEngine =
        NativePortmuxEngine(context = context, allowNonLoopbackTargets = allowAnyTarget)
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
        egress: EgressMode = EgressMode.NONE,
        egressArg: String = "",
        transport: NetTransport = NetTransport.ANY,
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
        return engine.addRoute(
            proto, listenAddr, listenPort, target, targetPort, egress, egressArg, transport,
        )
    }

    /**
     * Add a SOCKS5 proxy listener. A SOCKS proxy dials arbitrary destinations
     * supplied per-connection by the client, which is equivalent to permitting
     * arbitrary (including non-loopback) egress. It is therefore gated behind
     * [allowAnyTarget] and MUST NOT be enabled otherwise.
     *
     * [egress] optionally pins the proxy's upstream per connection: NONE
     * (default route), SOURCE (bind a local IP), or NETWORK (no-root pin to a
     * Wi-Fi/cellular/ethernet Android Network) — the "SOCKS over cellular/wifi"
     * capability. NETWORK/LAN targets require ACCESS_LOCAL_NETWORK where
     * applicable (C3) but pinning to a validated INTERNET network does not.
     */
    fun addSocks(
        listenAddr: String,
        listenPort: Int,
        egress: EgressMode = EgressMode.NONE,
        egressArg: String = "",
        transport: NetTransport = NetTransport.ANY,
    ): Route {
        if (!allowAnyTarget) {
            throw IllegalStateException(
                "SOCKS5 proxy implies arbitrary per-connection egress; set " +
                    "ALLOW_ANY_TARGET to enable it",
            )
        }
        return engine.addSocks(listenAddr, listenPort, egress, egressArg, transport)
    }

    /**
     * Add an encrypted tunnel CLIENT (NoisePSK_NN) that dials an arbitrary
     * remote server and splices to a local target (e.g. adbd). Reaching an
     * arbitrary off-device server is a powerful capability, so it is gated
     * behind [allowAnyTarget] exactly like the SOCKS proxy.
     * 
     * Reverse-forward (v0.4.0): revListenPort>0 opens a REVERSE LISTENER on the
     * client; the server connects to revTargetAddr:revTargetPort on a reverse
     * OPEN. Reverse targets are gated behind allowAnyTarget for non-loopback.
     *
     * Multi-hop jumps (v0.5.0): when jumpHops is non-empty this client is the
     * chain edge/originator. jumpHops[0] must equal serverAddr:serverPort.
     * The terminal jump target (jumpTargetAddr:jumpTargetPort) is a final
     * connect, so non-loopback is gated behind allowAnyTarget like forward and
     * reverse targets.
     */
    fun addTunnel(
        serverAddr: String,
        serverPort: Int,
        targetAddr: String,
        targetPort: Int,
        pskHex: String,
        cipher: Int = 0,
        egress: EgressMode = EgressMode.NONE,
        egressArg: String = "",
        transport: NetTransport = NetTransport.ANY,
        revListenAddr: String = "",
        revListenPort: Int = 0,
        revTargetAddr: String = "",
        revTargetPort: Int = 0,
        revProto: Int = 0,
        jumpHops: List<String> = emptyList(),
        jumpTargetAddr: String = "",
        jumpTargetPort: Int = 0,
        jumpProto: Int = 0,
    ): Route {
        if (!allowAnyTarget) {
            throw IllegalStateException(
                "encrypted tunnel reaches an arbitrary remote server; set " +
                    "ALLOW_ANY_TARGET to enable it",
            )
        }
        // Reverse target is a server-side connect; gate non-loopback behind allowAnyTarget
        if (revListenPort > 0) {
            val rtarget = revTargetAddr.ifBlank { "127.0.0.1" }
            if (!isLoopback(rtarget) && !allowAnyTarget) {
                throw IllegalArgumentException(
                    "reverse target '$rtarget' is not loopback; set ALLOW_ANY_TARGET to permit non-loopback reverse targets",
                )
            }
        }
        // Final jump target is a terminal connect; gate non-loopback behind allowAnyTarget
        if (jumpHops.isNotEmpty()) {
            val jtarget = jumpTargetAddr.ifBlank { "127.0.0.1" }
            if (!isLoopback(jtarget) && !allowAnyTarget) {
                throw IllegalArgumentException(
                    "jump target '$jtarget' is not loopback; set ALLOW_ANY_TARGET to permit non-loopback jump targets",
                )
            }
        }
        return engine.addTunnel(
            serverAddr, serverPort, targetAddr, targetPort, pskHex, cipher,
            egress, egressArg, transport,
            revListenAddr, revListenPort, revTargetAddr, revTargetPort, revProto,
            jumpHops, jumpTargetAddr, jumpTargetPort, jumpProto,
        )
    }

    /**
     * Add a relay PEER (v0.5.0): a single-port peek-demux tunnel server that
     * accepts encrypted carriers and relays OPENJ multi-hop streams onward (or
     * connects the terminal target). A peer can dial arbitrary next hops /
     * terminal targets, so — like the tunnel client and the SOCKS proxy — it is
     * gated behind [allowAnyTarget] and MUST NOT be enabled otherwise.
     */
    fun addPeer(
        listenAddr: String,
        listenPort: Int,
        pskHex: String,
        cipher: Int = 0,
        egress: EgressMode = EgressMode.NONE,
        egressArg: String = "",
        transport: NetTransport = NetTransport.ANY,
    ): Route {
        if (!allowAnyTarget) {
            throw IllegalStateException(
                "relay peer forwards to arbitrary next hops / terminal targets; " +
                    "set ALLOW_ANY_TARGET to enable it",
            )
        }
        return engine.addPeer(listenAddr, listenPort, pskHex, cipher, egress, egressArg, transport)
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
