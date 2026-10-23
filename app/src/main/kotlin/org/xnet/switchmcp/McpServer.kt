package org.xnet.switchmcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the MCP server exposing portmux control as tools, backed by
 * [controller]. The Streamable HTTP transport makes one [Server] per session,
 * all sharing the one controller (SPEC §3/§4).
 */
fun buildSwitchMcpServer(controller: PortmuxController): Server {
    val server = Server(
        Implementation(name = "switch-mcp", version = "0.3.0"),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    fun ok(text: String) = CallToolResult(content = listOf(TextContent(text)))
    fun err(text: String) =
        CallToolResult(content = listOf(TextContent("error: $text")), isError = true)

    // Parse the shared egress/transport args. egress: "none"|"default" (default),
    // "source" (egress_arg = local source IP), or a transport name
    // "wifi"|"cellular"|"ethernet" (NETWORK pin to that Android Network, no root).
    // Returns Triple(mode, arg, transport).
    fun parseEgress(a: Map<String, kotlinx.serialization.json.JsonElement>):
        Triple<EgressMode, String, NetTransport> {
        val egress = a["egress"]?.jsonPrimitive?.content?.lowercase()?.trim()
        val egressArg = a["egress_arg"]?.jsonPrimitive?.content ?: ""
        return when (egress) {
            null, "", "none", "default" -> Triple(EgressMode.NONE, "", NetTransport.ANY)
            "source", "src" -> Triple(EgressMode.SOURCE, egressArg, NetTransport.ANY)
            "wifi" -> Triple(EgressMode.NETWORK, "", NetTransport.WIFI)
            "cellular", "cell", "mobile" -> Triple(EgressMode.NETWORK, "", NetTransport.CELLULAR)
            "ethernet", "eth" -> Triple(EgressMode.NETWORK, "", NetTransport.ETHERNET)
            else -> error("egress must be one of: none|source|wifi|cellular|ethernet")
        }
    }

    // Shared schema fragment for the egress/transport args.
    fun putEgressProps(b: kotlinx.serialization.json.JsonObjectBuilder) {
        b.putJsonObject("egress") {
            put("type", "string")
            put(
                "description",
                "upstream egress: none (default route) | source (bind local IP " +
                    "via egress_arg) | wifi | cellular | ethernet (no-root pin " +
                    "the upstream to that Android network; survives handoffs, " +
                    "fails closed if the network is gone)",
            )
        }
        b.putJsonObject("egress_arg") {
            put("type", "string")
            put("description", "for egress=source: the local source IP to bind")
        }
    }

    // --- add_route ---
    server.addTool(
        name = "add_route",
        description = "Add a port-forwarding route. proto is 'tcp' or 'udp'. " +
            "Targets must be loopback unless the server was started with " +
            "ALLOW_ANY_TARGET (and non-loopback LAN targets also need " +
            "ACCESS_LOCAL_NETWORK). If forwarding is running, the route takes " +
            "effect immediately.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("proto") {
                    put("type", "string"); put("description", "tcp | udp")
                }
                putJsonObject("listen_addr") {
                    put("type", "string"); put("description", "listen address (default 127.0.0.1)")
                }
                putJsonObject("listen_port") {
                    put("type", "integer"); put("description", "local port to listen on")
                }
                putJsonObject("target_addr") {
                    put("type", "string"); put("description", "target address (default 127.0.0.1)")
                }
                putJsonObject("target_port") {
                    put("type", "integer"); put("description", "target port to forward to")
                }
                putEgressProps(this)
            },
            required = listOf("proto", "listen_port", "target_port"),
        ),
    ) { request ->
        try {
            val a = request.arguments ?: error("missing arguments")
            val proto = when (a["proto"]?.jsonPrimitive?.content?.lowercase()) {
                "udp" -> Proto.UDP
                "tcp", null -> Proto.TCP
                else -> error("proto must be tcp or udp")
            }
            val (egress, egressArg, transport) = parseEgress(a)
            val route = controller.addRoute(
                proto = proto,
                listenAddr = a["listen_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                listenPort = a["listen_port"]!!.jsonPrimitive.content.toInt(),
                targetAddr = a["target_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                targetPort = a["target_port"]!!.jsonPrimitive.content.toInt(),
                egress = egress,
                egressArg = egressArg,
                transport = transport,
            )
            ok("added route ${route.id}: ${route.summary()}")
        } catch (t: Throwable) {
            err(t.message ?: t.toString())
        }
    }

    // --- add_socks ---
    server.addTool(
        name = "add_socks",
        description = "Add a SOCKS5 proxy listener (RFC1928 no-auth, CONNECT and " +
            "UDP ASSOCIATE). WARNING: this creates an OPEN PROXY that dials " +
            "ARBITRARY destinations chosen per-connection by the client, so it " +
            "implies arbitrary egress and REQUIRES the server to have been " +
            "started with ALLOW_ANY_TARGET (otherwise it is rejected). Binds " +
            "loopback by default; do not bind below port 1024. The optional " +
            "egress arg pins every upstream connection to a chosen network " +
            "(wifi|cellular|ethernet) with no root — e.g. a SOCKS proxy whose " +
            "traffic egresses over cellular. If forwarding is running, the " +
            "listener takes effect immediately.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("listen_addr") {
                    put("type", "string"); put("description", "listen address (default 127.0.0.1)")
                }
                putJsonObject("listen_port") {
                    put("type", "integer"); put("description", "local port to listen on (e.g. 1080)")
                }
                putEgressProps(this)
            },
            required = listOf("listen_port"),
        ),
    ) { request ->
        try {
            val a = request.arguments ?: error("missing arguments")
            val (egress, egressArg, transport) = parseEgress(a)
            val route = controller.addSocks(
                listenAddr = a["listen_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                listenPort = a["listen_port"]!!.jsonPrimitive.content.toInt(),
                egress = egress,
                egressArg = egressArg,
                transport = transport,
            )
            ok("added socks ${route.id}: ${route.summary()}")
        } catch (t: Throwable) {
            err(t.message ?: t.toString())
        }
    }

    // --- remove_route ---
    server.addTool(
        name = "remove_route",
        description = "Remove a route by its id.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("id") { put("type", "string"); put("description", "route id") }
            },
            required = listOf("id"),
        ),
    ) { request ->
        try {
            val id = request.arguments?.get("id")?.jsonPrimitive?.content ?: error("missing id")
            if (controller.removeRoute(id)) ok("removed $id") else err("no such route: $id")
        } catch (t: Throwable) {
            err(t.message ?: t.toString())
        }
    }

    // --- list_routes ---
    server.addTool(
        name = "list_routes",
        description = "List all configured routes.",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
    ) {
        val routes = controller.listRoutes()
        if (routes.isEmpty()) ok("(no routes)")
        else ok(routes.joinToString("\n") { "${it.id}  ${it.summary()}" })
    }

    // --- status ---
    server.addTool(
        name = "status",
        description = "Show running state, route count, Network-permission state " +
            "(whether INTERNET is granted — a revoked Network permission kills " +
            "the forwarder), the detected deployment role (normal | privileged), " +
            "and whether ALLOW_ANY_TARGET is set (required for non-loopback " +
            "targets and for the add_socks SOCKS5 proxy).",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
    ) {
        ok(controller.status().describe())
    }

    // --- start ---
    server.addTool(
        name = "start",
        description = "Start forwarding all configured routes.",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
    ) {
        try {
            controller.start(); ok("started")
        } catch (t: Throwable) {
            err(t.message ?: t.toString())
        }
    }

    // --- stop ---
    server.addTool(
        name = "stop",
        description = "Stop forwarding.",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
    ) {
        controller.stop(); ok("stopped")
    }

    return server
}
