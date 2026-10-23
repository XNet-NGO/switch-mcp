package org.xnet.switchmcp

/** Transport protocol for a route. */
enum class Proto { TCP, UDP }

/**
 * How a route's upstream (target) socket selects its egress path. Mirrors
 * portmux's PortmuxEgress kinds. switch-mcp exposes NONE / SOURCE / NETWORK to
 * agents; DEVICE needs root/CAP_NET_RAW and is not offered here.
 */
enum class EgressMode { NONE, SOURCE, NETWORK }

/** Transport to requestNetwork() for a NETWORK-egress route. */
enum class NetTransport { ANY, WIFI, CELLULAR, ETHERNET }

/**
 * A single forwarding rule. A SOCKS5 proxy listener is also a [Route], with
 * [isSocks] set; it has no fixed target (the per-connection destination is
 * supplied by the SOCKS client), so [targetAddr]/[targetPort] are unused for it.
 *
 * [egress] selects how every upstream socket leaves the device:
 *  - NONE: default route / loopback.
 *  - SOURCE: bind to local source IP [egressArg] (no privilege).
 *  - NETWORK: pin to an Android Network of [transport] via
 *    android_setsocknetwork (no root) — the "forward/SOCKS over cellular/wifi"
 *    capability. The handle is refreshed live across network changes.
 */
data class Route(
    val id: String,
    val proto: Proto,
    val listenAddr: String,
    val listenPort: Int,
    val targetAddr: String,
    val targetPort: Int,
    val isSocks: Boolean = false,
    val egress: EgressMode = EgressMode.NONE,
    val egressArg: String = "",
    val transport: NetTransport = NetTransport.ANY,
    /** True if this is an encrypted tunnel CLIENT: it dials [serverAddr]:
     *  [serverPort] over NoisePSK_NN and splices to [targetAddr]:[targetPort].
     *  [listenPort] is unused for tunnels. */
    val isTunnel: Boolean = false,
    val serverAddr: String = "",
    val serverPort: Int = 0,
) {
    fun summary(): String {
        val eg = when (egress) {
            EgressMode.NONE -> ""
            EgressMode.SOURCE -> "  src=$egressArg"
            EgressMode.NETWORK -> "  via ${transport.name.lowercase()}"
        }
        if (isTunnel) return "tunnel  -> $serverAddr:$serverPort => $targetAddr:$targetPort (encrypted)$eg"
        if (isSocks) return "socks5  $listenAddr:$listenPort -> (per-connection)$eg"
        val p = if (proto == Proto.TCP) "tcp" else "udp"
        return "$p  $listenAddr:$listenPort -> $targetAddr:$targetPort$eg"
    }
}

/**
 * Backend-agnostic control surface over the portmux engine. On Android the only
 * implementation is [NativePortmuxEngine] (in-process via JNI over the embedded
 * libportmux.a). Mirrors the interface from portmux-mcp so the MCP tool layer
 * is identical.
 */
interface PortmuxEngine {
    /** Add a route; returns it. Throws on validation/engine error. */
    fun addRoute(
        proto: Proto,
        listenAddr: String,
        listenPort: Int,
        targetAddr: String,
        targetPort: Int,
        egress: EgressMode = EgressMode.NONE,
        egressArg: String = "",
        transport: NetTransport = NetTransport.ANY,
    ): Route

    /**
     * Add a SOCKS5 proxy listener; returns it. A SOCKS proxy dials arbitrary
     * per-connection destinations supplied by the client, so callers MUST
     * enforce their own egress/target policy before calling. Throws on
     * validation/engine error.
     */
    fun addSocks(
        listenAddr: String,
        listenPort: Int,
        egress: EgressMode = EgressMode.NONE,
        egressArg: String = "",
        transport: NetTransport = NetTransport.ANY,
    ): Route

    /**
     * Add an encrypted tunnel CLIENT: dial [serverAddr]:[serverPort] over
     * NoisePSK_NN (authenticated by [pskHex], 64 hex chars) and splice the
     * decrypted stream to [targetAddr]:[targetPort]. [cipher] 0=AES-GCM,
     * 1=ChaCha20-Poly1305. The outbound leg may be egress-pinned. Reaching an
     * arbitrary remote server is sensitive, so callers MUST gate it.
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
    ): Route

    /** Remove a route by id. Returns true if it existed. */
    fun removeRoute(id: String): Boolean

    /** Current routes. */
    fun listRoutes(): List<Route>

    /** Whether forwarding is currently running. */
    fun isRunning(): Boolean

    /** Start forwarding all configured routes. */
    fun start()

    /** Stop forwarding. */
    fun stop()
}
