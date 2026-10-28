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
        Implementation(name = "switch-mcp", version = "0.4.0"),
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

    // --- add_tunnel ---
    server.addTool(
        name = "add_tunnel",
        description = "Add an ENCRYPTED TUNNEL client (Noise NoisePSK_NN: ephemeral " +
            "X25519 + 32-byte pre-shared key; AES-256-GCM or ChaCha20-Poly1305). " +
            "Dials an arbitrary remote server over one outbound connection and " +
            "splices the decrypted stream to a local target (default " +
            "127.0.0.1:5555, i.e. adbd) — e.g. expose this device's adb to a " +
            "remote box with no TUN and no root. WARNING: reaching an arbitrary " +
            "off-device server is powerful and REQUIRES the server to have been " +
            "started with ALLOW_ANY_TARGET. The optional egress pins the outbound " +
            "leg to a chosen network (wifi|cellular|ethernet). REVERSE-FORWARD " +
            "(v0.4.0): rlisten_port>0 opens a REVERSE LISTENER on the CLIENT " +
            "(ssh -R style): connections there become client-originated reverse " +
            "streams the server splices to rtarget_addr:rtarget_port. rproto sets " +
            "the reverse listener protocol: tcp (default), udp, or both (dual-stack). " +
            "MULTI-HOP JUMPS (v0.5.0): pass jumps as an array of \"addr:port\" " +
            "next-hops (or jump_hops as a comma-separated string) to make this " +
            "client the chain EDGE/originator. jumps[0] MUST equal " +
            "server_addr:server_port (the carrier dest); the OPENJ carries the " +
            "remaining hops + jump_target_addr:jump_target_port (final dest). " +
            "jump_proto is the final leg: tcp (default) or udp. A jump chain " +
            "requires a reverse listener (rlisten_port>0) as the local plaintext " +
            "entry that emits OPENJ. Relay the chain through add_peer nodes.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("server_addr") {
                    put("type", "string"); put("description", "rendezvous server IP/host to dial")
                }
                putJsonObject("server_port") {
                    put("type", "integer"); put("description", "rendezvous server port")
                }
                putJsonObject("psk") {
                    put("type", "string")
                    put("description", "pre-shared key, 64 hex chars (32 bytes); must match the server")
                }
                putJsonObject("target_addr") {
                    put("type", "string"); put("description", "local target address (default 127.0.0.1)")
                }
                putJsonObject("target_port") {
                    put("type", "integer"); put("description", "local target port (default 5555 = adbd)")
                }
                putJsonObject("cipher") {
                    put("type", "string"); put("description", "aesgcm (default) | chacha")
                }
                putEgressProps(this)
                putJsonObject("rlisten_addr") {
                    put("type", "string")
                    put("description", "reverse listener address on the client (default 127.0.0.1; v0.4.0)")
                }
                putJsonObject("rlisten_port") {
                    put("type", "integer")
                    put("description", "reverse listener port on the client (0=none; v0.4.0)")
                }
                putJsonObject("rtarget_addr") {
                    put("type", "string")
                    put("description", "reverse target address on the server (default 127.0.0.1; v0.4.0)")
                }
                putJsonObject("rtarget_port") {
                    put("type", "integer")
                    put("description", "reverse target port on the server (required if rlisten_port>0; v0.4.0)")
                }
                putJsonObject("rproto") {
                    put("type", "string")
                    put("description", "reverse listener protocol: tcp (default), udp, or both (dual-stack; v0.4.0)")
                }
                putJsonObject("jumps") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put(
                        "description",
                        "multi-hop jump list: ordered \"addr:port\" next-hops (v0.5.0). " +
                            "jumps[0] MUST equal server_addr:server_port. Requires rlisten_port>0.",
                    )
                }
                putJsonObject("jump_hops") {
                    put("type", "string")
                    put("description", "alternative to jumps: comma-separated \"addr:port\" next-hops (v0.5.0)")
                }
                putJsonObject("jump_target_addr") {
                    put("type", "string")
                    put("description", "final jump destination address (default 127.0.0.1; v0.5.0)")
                }
                putJsonObject("jump_target_port") {
                    put("type", "integer")
                    put("description", "final jump destination port (required if jumps set; v0.5.0)")
                }
                putJsonObject("jump_proto") {
                    put("type", "string")
                    put("description", "final jump leg protocol: tcp (default) or udp (v0.5.0)")
                }
            },
            required = listOf("server_addr", "server_port", "psk"),
        ),
    ) { request ->
        try {
            val a = request.arguments ?: error("missing arguments")
            val (egress, egressArg, transport) = parseEgress(a)
            val cipher = when (a["cipher"]?.jsonPrimitive?.content?.lowercase()) {
                "chacha", "chachapoly" -> 1
                else -> 0
            }
            val rlistenPort = a["rlisten_port"]?.jsonPrimitive?.content?.toInt() ?: 0
            val rtargetPort = a["rtarget_port"]?.jsonPrimitive?.content?.toInt() ?: 0
            val rproto = when (a["rproto"]?.jsonPrimitive?.content?.lowercase()?.trim()) {
                "udp" -> 1
                "both", "tcp+udp", "dual" -> 2
                else -> 0
            }
            // Jumps: accept either a JSON array `jumps` or a comma-separated
            // `jump_hops` string. Each entry is "addr:port".
            val jumpHops: List<String> = run {
                val arr = a["jumps"] as? kotlinx.serialization.json.JsonArray
                if (arr != null) {
                    arr.map { it.jsonPrimitive.content.trim() }.filter { it.isNotEmpty() }
                } else {
                    a["jump_hops"]?.jsonPrimitive?.content
                        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                        ?: emptyList()
                }
            }
            val jumpTargetPort = a["jump_target_port"]?.jsonPrimitive?.content?.toInt() ?: 0
            val jumpProto = when (a["jump_proto"]?.jsonPrimitive?.content?.lowercase()?.trim()) {
                "udp" -> 1
                else -> 0
            }
            val route = controller.addTunnel(
                serverAddr = a["server_addr"]!!.jsonPrimitive.content,
                serverPort = a["server_port"]!!.jsonPrimitive.content.toInt(),
                targetAddr = a["target_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                targetPort = a["target_port"]?.jsonPrimitive?.content?.toInt() ?: 5555,
                pskHex = a["psk"]!!.jsonPrimitive.content.trim(),
                cipher = cipher,
                egress = egress,
                egressArg = egressArg,
                transport = transport,
                revListenAddr = a["rlisten_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                revListenPort = rlistenPort,
                revTargetAddr = a["rtarget_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                revTargetPort = rtargetPort,
                revProto = rproto,
                jumpHops = jumpHops,
                jumpTargetAddr = a["jump_target_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                jumpTargetPort = jumpTargetPort,
                jumpProto = jumpProto,
            )
            ok("added tunnel ${route.id}: ${route.summary()}")
        } catch (t: Throwable) {
            err(t.message ?: t.toString())
        }
    }

    // --- add_peer ---
    server.addTool(
        name = "add_peer",
        description = "Add a relay PEER (v0.5.0): a single-port peek-demux tunnel " +
            "server (Noise NoisePSK_NN) that accepts encrypted carriers on " +
            "listen_port and either relays an OPENJ multi-hop stream to the next " +
            "hop or connects the terminal target when no hops remain. Peers form " +
            "the intermediate/terminal nodes of a jump chain whose edge is an " +
            "add_tunnel with jumps set; every node in the chain shares one psk/" +
            "cipher. WARNING: a peer forwards to arbitrary next hops / terminal " +
            "targets, so it REQUIRES the server to have been started with " +
            "ALLOW_ANY_TARGET (otherwise it is rejected). The optional egress " +
            "pins the peer's onward leg to a chosen network (wifi|cellular|ethernet).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("listen_addr") {
                    put("type", "string")
                    put("description", "listen address for encrypted carriers (default 127.0.0.1)")
                }
                putJsonObject("listen_port") {
                    put("type", "integer"); put("description", "local port to accept encrypted carriers on")
                }
                putJsonObject("psk") {
                    put("type", "string")
                    put("description", "pre-shared key, 64 hex chars (32 bytes); shared by the whole chain")
                }
                putJsonObject("cipher") {
                    put("type", "string"); put("description", "aesgcm (default) | chacha")
                }
                putEgressProps(this)
            },
            required = listOf("listen_port", "psk"),
        ),
    ) { request ->
        try {
            val a = request.arguments ?: error("missing arguments")
            val (egress, egressArg, transport) = parseEgress(a)
            val cipher = when (a["cipher"]?.jsonPrimitive?.content?.lowercase()) {
                "chacha", "chachapoly" -> 1
                else -> 0
            }
            val route = controller.addPeer(
                listenAddr = a["listen_addr"]?.jsonPrimitive?.content ?: "127.0.0.1",
                listenPort = a["listen_port"]!!.jsonPrimitive.content.toInt(),
                pskHex = a["psk"]!!.jsonPrimitive.content.trim(),
                cipher = cipher,
                egress = egress,
                egressArg = egressArg,
                transport = transport,
            )
            ok("added peer ${route.id}: ${route.summary()}")
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
