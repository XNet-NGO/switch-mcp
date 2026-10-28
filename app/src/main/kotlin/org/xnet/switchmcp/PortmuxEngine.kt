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
    /** Reverse-forward (v0.4.0): [revListenAddr]:[revListenPort] opens a
     *  REVERSE LISTENER on the client (when revListenPort>0). Connections there
     *  become client-originated reverse streams the server splices to
     *  [revTargetAddr]:[revTargetPort]. [revProto] sets the reverse listener
     *  protocol: 0=TCP, 1=UDP, 2=BOTH (dual-stack). */
    val revListenAddr: String = "",
    val revListenPort: Int = 0,
    val revTargetAddr: String = "",
    val revTargetPort: Int = 0,
    val revProto: Int = 0,
    /** True if this is a relay PEER (v0.5.0): a single-port peek-demux tunnel
     *  server that relays OPENJ multi-hop streams to the next hop or connects
     *  the terminal target. A peer listens on [listenAddr]:[listenPort] for
     *  encrypted carriers; it has no fixed forward target of its own. */
    val isPeer: Boolean = false,
    /** Multi-hop jumps (v0.5.0): when non-empty on an EDGE tunnel client, the
     *  ordered next-hops as "addr:port" strings. hops[0] MUST equal the
     *  client's carrier dest ([serverAddr]:[serverPort]). The OPENJ carries the
     *  remaining hops + [jumpTargetAddr]:[jumpTargetPort]. [jumpProto] is the
     *  final leg: 0=TCP, 1=UDP. */
    val jumpHops: List<String> = emptyList(),
    val jumpTargetAddr: String = "",
    val jumpTargetPort: Int = 0,
    val jumpProto: Int = 0,
) {
    fun summary(): String {
        val eg = when (egress) {
            EgressMode.NONE -> ""
            EgressMode.SOURCE -> "  src=$egressArg"
            EgressMode.NETWORK -> "  via ${transport.name.lowercase()}"
        }
        if (isPeer) {
            return "peer  $listenAddr:$listenPort (relay/peek-demux, encrypted)$eg"
        }
        if (isTunnel) {
            val fwd = "tunnel  -> $serverAddr:$serverPort => $targetAddr:$targetPort (encrypted)$eg"
            val base = if (jumpHops.isNotEmpty()) {
                val jp = if (jumpProto == 1) "udp" else "tcp"
                "$fwd + jumps [${jumpHops.joinToString(" -> ")}] => $jumpTargetAddr:$jumpTargetPort ($jp)"
            } else {
                fwd
            }
            if (revListenPort > 0) {
                val rprotoStr = when (revProto) {
                    1 -> "udp"
                    2 -> "tcp+udp"
                    else -> "tcp"
                }
                return "$base + reverse $rprotoStr $revListenAddr:$revListenPort <- $revTargetAddr:$revTargetPort"
            }
            return base
        }
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
     * 
     * Reverse-forward (v0.4.0): [revListenAddr]:[revListenPort] opens a REVERSE
     * LISTENER on the client (when revListenPort>0). Connections there become
     * client-originated reverse streams the server splices to
     * [revTargetAddr]:[revTargetPort]. [revProto] sets the reverse listener
     * protocol: 0=TCP, 1=UDP, 2=BOTH (dual-stack TCP+UDP on one port).
     *
     * Multi-hop jumps (v0.5.0): when [jumpHops] is non-empty, this edge client
     * is the chain originator. [jumpHops] are the ordered next-hops as
     * "addr:port"; hops[0] MUST equal [serverAddr]:[serverPort] (the carrier
     * dest). The decrypted OPENJ carries the remaining hops +
     * [jumpTargetAddr]:[jumpTargetPort] (the final destination). [jumpProto] is
     * the final leg: 0=TCP, 1=UDP. The edge client MUST have a reverse listener
     * ([revListenPort]>0) which becomes the local plaintext entry emitting OPENJ.
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
    ): Route

    /**
     * Add a relay PEER (v0.5.0): a single-port peek-demux tunnel server that
     * accepts encrypted carriers on [listenAddr]:[listenPort] and either relays
     * an OPENJ multi-hop stream to the next hop (honoring [egress]) or connects
     * the terminal target when no hops remain. Shares the single [pskHex]/
     * [cipher] with every peer in the chain. [cipher] 0=AES-GCM,
     * 1=ChaCha20-Poly1305. Reaching/relaying to arbitrary next hops is
     * sensitive, so callers MUST gate it.
     */
    fun addPeer(
        listenAddr: String,
        listenPort: Int,
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
