package org.xnet.switchmcp

/** Transport protocol for a route. */
enum class Proto { TCP, UDP }

/** A single forwarding rule. */
data class Route(
    val id: String,
    val proto: Proto,
    val listenAddr: String,
    val listenPort: Int,
    val targetAddr: String,
    val targetPort: Int,
) {
    fun summary(): String {
        val p = if (proto == Proto.TCP) "tcp" else "udp"
        return "$p  $listenAddr:$listenPort -> $targetAddr:$targetPort"
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
