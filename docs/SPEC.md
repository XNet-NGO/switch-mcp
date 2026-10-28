# switch-mcp — Specification

**Status:** draft v0.1 · **Target:** Android 17 (API 37), GrapheneOS and AOSP
**Repo:** `XNet-NGO/switch-mcp`

---

## 1. Purpose

`switch-mcp` is a **headless Android app** that runs an **MCP server in-process**
so an MCP client (a desktop/cloud agent, another device, or an on-device agent)
can create and manage **userspace port forwards** on the phone over the
network, with no root and no `VpnService`/TUN.

It fuses two existing XNet components:

- the **portmux** forwarding engine (embedded via JNI from portmux's Android
  static-library release artifacts), and
- an **MCP server** (official Kotlin MCP SDK) speaking **Streamable HTTP**,

hosted inside a foreground service. It is the "agent-controllable" sibling of
the Switchboard app: Switchboard is the human UI, `switch-mcp` is the headless
control plane.

### Dual purpose

`switch-mcp` is designed to serve **two deployment roles from one codebase**:

1. **Normal app** — installed like any APK on stock/GrapheneOS, running within
   the standard FGS/boot/background limits (the v0.1 verified path).
2. **System-privileged app** — the same build placed in `priv-app`
   (platform-signed, on the privileged-permission allowlist) on an XNet /
   GrapheneOS-derived OS, where it runs unattended as a system-level service
   with boot-start and background limits lifted.

These are not separate products: the app ships the manifest and allowlist hooks
for the privileged role and detects which role it is running in at runtime,
adapting its lifecycle behavior accordingly (§7, §9). The privileged role is a
first-class purpose of the project, not merely a documented possibility — it is
simply **out of automated verification scope for v0.1** because it requires a
custom OS build (C7).

### Non-goals (v0.1)

- No UI beyond what headless operation requires (see §6).
- No remote/LAN exposure of the MCP endpoint by default (loopback only, §5).
- No *dependency* on privileged/system-app deployment for the v0.1 verified
  path: the privileged role is a first-class purpose and the build ships its
  hooks (§9), but it is out of automated verification scope for v0.1 because it
  needs a custom OS build (C7). The normal-app role must work without it.
- No AppFunctions implementation yet — only a seam for it (§8).

---

## 2. Verified constraints (on-device, GrapheneOS API 37)

These were established empirically on the target device and drive the design.
They are requirements, not assumptions.

| # | Finding | Source | Impact |
|---|---------|--------|--------|
| C1 | **INTERNET is required even for a loopback listener.** With INTERNET denied, the kernel tears down the app uid's sockets ("Destroyed live tcp sockets for uid"); no loopback bind. With it granted, the loopback listener opens. | A/B test on-device | The app cannot function without INTERNET; must detect denial and report it (§5, §6). |
| C2 | **GrapheneOS makes INTERNET a revocable `dangerous` permission** (its per-app "Network" toggle). | `dumpsys package` (`prot=dangerous`, `USER_SET` flags) | INTERNET is not guaranteed; handle revocation gracefully. |
| C3 | **`ACCESS_LOCAL_NETWORK` is a separate `dangerous` runtime permission** (API 37). Loopback is exempt from it; LAN targets are not. | `dumpsys package` | Request at runtime only when a route's target is non-loopback (§5). |
| C4 | **Native portmux code runs under GrapheneOS hardening** (hardened_malloc/MTE) without issue. | Switchboard's embedded engine ran on-device earlier | Reuse portmux `.a` + JNI as-is. |
| C5 | **FGS-from-boot is AOSP-restricted; `specialUse` is not boot-allowed.** No default boot whitelist. | `dumpsys deviceidle`, AOSP policy | Boot start needs a battery exemption or a two-step promote (§7). |
| C6 | **Per-app battery/doze exemption is available and grantable.** | `dumpsys deviceidle whitelist` populated | Rely on user/ROM exemption for reliable background run (§7). |
| C7 | **Locked bootloader, verified-boot yellow (user key)** — normal GrapheneOS, no privileged microG-style slot. | `ro.boot.*` | Privileged deployment requires a custom OS build; not assumed (§9). |

---

## 3. Architecture

```
┌──────────────────────── switch-mcp (one app process) ───────────────────────┐
│                                                                              │
│  BootReceiver / StarterActivity ──> McpForegroundService                     │
│                                        │                                     │
│                   ┌────────────────────┼─────────────────────┐              │
│                   │ Ktor embeddedServer │ NetworkGuard        │              │
│                   │  mcpStreamableHttp  │ (INTERNET state)    │              │
│                   │   @ 127.0.0.1:PORT  │                     │              │
│                   └─────────┬───────────┘                     │              │
│                             │ MCP tools                       │              │
│                       PortmuxController  ◄──── (AppFunctions seam, §8)       │
│                             │ JNI                                            │
│                       libswitchmcpjni.so                                     │
│                        ├─ switch-mcp JNI shim                                │
│                        ├─ libportmux.a        (engine, from portmux release) │
│                        └─ libhev-task-system.a                               │
└──────────────────────────────────────────────────────────────────────────────┘
          ▲ Streamable HTTP (loopback)
          │
   MCP client reaches it via on-device, `adb forward`, or a user-run tunnel
```

### Components

- **`McpForegroundService`** — owns the Ktor server and the engine; runs for
  the app's active lifetime; posts the ongoing notification (§6).
- **Ktor `embeddedServer` + `mcpStreamableHttp`** — single `/mcp` endpoint on
  `127.0.0.1:<port>`; one MCP `Server` per session, all sharing one controller.
- **`PortmuxController`** — the single source of truth for routes and engine
  state; the MCP tools and (later) AppFunctions both call it. Mirrors the
  `PortmuxEngine` interface from `portmux-mcp`.
- **`NativePortmuxEngine`** — JNI over the embedded portmux static libs
  (reuses the Switchboard/portmux-mcp JNI pattern).
- **`NetworkGuard`** — checks INTERNET grant state (C1/C2) and drives the
  notification/report when Network is denied.
- **`BootReceiver` + `StarterActivity`** — headless start path (§7).

---

## 4. MCP interface

Transport: **Streamable HTTP** (current MCP standard), endpoint
`http://127.0.0.1:<port>/mcp`. Protocol revision per the Kotlin SDK (0.15.x).

### Tools

| Tool | Args | Result |
|------|------|--------|
| `add_route` | `proto` (tcp/udp), `listen_addr?`, `listen_port`, `target_addr?`, `target_port` | route id + summary |
| `add_socks` | `listen_addr?`, `listen_port` | socks route id + summary |
| `add_tunnel` | `server_addr`, `server_port`, `psk`, `target_addr?`, `target_port?`, `cipher?`, `rlisten_addr?`, `rlisten_port?`, `rtarget_addr?`, `rtarget_port?`, `rproto?`, `jumps?`, `jump_hops?`, `jump_target_addr?`, `jump_target_port?`, `jump_proto?` | tunnel route id + summary |
| `add_peer` | `listen_addr?`, `listen_port`, `psk`, `cipher?` | peer route id + summary |
| `remove_route` | `id` | ok / not-found |
| `list_routes` | — | route list |
| `status` | — | running state, route count, **Network-permission state**, **deployment role** (normal / privileged, §9), **allowAnyTarget** |
| `start` | — | start forwarding |
| `stop` | — | stop forwarding |

`status` MUST surface whether INTERNET is granted (C1), so a client can
diagnose a dead forwarder caused by a revoked Network permission.

`add_socks` registers an **RFC1928 SOCKS5 proxy** (no-auth, CONNECT and UDP
ASSOCIATE) whose per-connection destination is chosen by the client — i.e. an
open proxy to arbitrary destinations. Because that is equivalent to arbitrary
egress, it is **gated behind `ALLOW_ANY_TARGET`** and is rejected unless that
flag is set (§4 safety defaults, §12). Every upstream socket the proxy opens
still passes through the route's egress policy, so Android per-connection egress
pinning (`PORTMUX_EGRESS_CALLBACK`) applies exactly as for normal routes.

`add_tunnel` registers an **encrypted tunnel CLIENT** (NoisePSK_NN over
portmux v0.4.0+). The traditional forward-only tunnel dials `server_addr:server_port`
and splices the decrypted stream to `target_addr:target_port` (e.g. 127.0.0.1:5555
adbd). **Reverse-forward (v0.4.0)**: when `rlisten_port>0`, the client opens a
REVERSE LISTENER at `rlisten_addr:rlisten_port` (ssh -R style). Connections
there become client-originated reverse streams the server splices to
`rtarget_addr:rtarget_port` on the server side. `rproto` sets the reverse
listener protocol: `tcp` (default), `udp`, or `both` (dual-stack TCP+UDP on one
port). Non-loopback reverse targets on the server are gated behind
`ALLOW_ANY_TARGET` just like forward targets, maintaining the existing safety
posture.

**Multi-hop jumps (v0.5.0)**: `add_tunnel` can make the client the EDGE
originator of a jump chain by passing `jumps` (a JSON array of `"addr:port"`
next-hops) or `jump_hops` (the same list comma-separated). `jumps[0]` MUST
equal `server_addr:server_port` (the carrier dest); the decrypted OPENJ carries
the remaining hops plus the final destination
`jump_target_addr:jump_target_port`, with `jump_proto` (`tcp` default / `udp`)
as the final leg. A jump chain REQUIRES a reverse listener (`rlisten_port>0`) as
the local plaintext entry that emits OPENJ. The terminal jump target is a final
connect, so a non-loopback `jump_target_addr` is gated behind `ALLOW_ANY_TARGET`
exactly like forward and reverse targets.

`add_peer` registers a relay **PEER** (portmux v0.5.0): a single-port
peek-demux tunnel server (Noise NoisePSK_NN) that accepts encrypted carriers on
`listen_port` and either relays an OPENJ multi-hop stream to the next hop or
connects the terminal target when no hops remain. Peers are the intermediate
and terminal nodes of a jump chain whose edge is an `add_tunnel` with `jumps`
set; every node in the chain shares one `psk`/`cipher`. Because a peer forwards
to arbitrary next hops / terminal targets, it is **gated behind
`ALLOW_ANY_TARGET`** and is rejected unless that flag is set (§12). The optional
`egress` pins the peer's onward leg to a chosen network.

### Safety defaults

- **Loopback targets only** unless explicitly enabled (config flag /
  `ALLOW_ANY_TARGET`), matching portmux-mcp. Non-loopback targets additionally
  require `ACCESS_LOCAL_NETWORK` to be granted (C3).
- **SOCKS5 proxy (`add_socks`) requires `ALLOW_ANY_TARGET`.** A SOCKS proxy
  dials arbitrary per-connection destinations, so enabling it implies arbitrary
  egress; it is rejected unless `ALLOW_ANY_TARGET` is set (§12).
- **Relay peer (`add_peer`) requires `ALLOW_ANY_TARGET`.** A peer forwards to
  arbitrary next hops / terminal targets, so it is rejected unless
  `ALLOW_ANY_TARGET` is set (§12). Multi-hop jump targets on `add_tunnel` are
  gated the same way.

---

## 5. Networking & permissions

- **Bind:** `127.0.0.1` only (both IPv4 `127.0.0.1` and IPv6 `::1`
  SHOULD be supported). Never `0.0.0.0` in v0.1.
- **INTERNET:** declared; required at runtime (C1). On GrapheneOS it is
  user-revocable (C2). If denied, the server MUST NOT crash — it reports the
  denied state via `status` and the notification and keeps retrying/listening
  for a grant.
- **ACCESS_LOCAL_NETWORK:** declared; requested at runtime only when a route
  targets a non-loopback address (C3). Loopback forwarding never needs it.
- **Reachability for remote clients:** out of scope to provide; documented
  patterns are on-device clients, `adb forward tcp:P tcp:P`, or a
  user-established tunnel. (Loopback-only is a deliberate safety choice.)

### Manifest permissions

```
INTERNET                         (required; GrapheneOS-revocable)
ACCESS_NETWORK_STATE
ACCESS_LOCAL_NETWORK             (runtime; only exercised for LAN targets)
FOREGROUND_SERVICE
FOREGROUND_SERVICE_SPECIAL_USE
POST_NOTIFICATIONS
RECEIVE_BOOT_COMPLETED
```

No location, no NEARBY_WIFI_DEVICES, no VpnService/BIND_VPN_SERVICE.

---

## 6. Headless UX & reporting

"Headless" = **no launcher icon** (no `LAUNCHER` intent-filter). The app has no
normal UI. Because it is headless, it MUST remain diagnosable:

- **Foreground-service notification** (required for the FGS; also the only
  always-visible status surface). Shows: server URL, running/stopped, route
  count, and — critically — **"Network permission denied" when INTERNET is
  revoked** (C1/C2).
- **`status` MCP tool** returns the same machine-readable state.
- Logcat tag `switch-mcp` for all lifecycle/error events.

A minimal, iconless `StarterActivity` exists only to (a) be an `adb`/intent
entry point to start the service and (b) host the runtime-permission request
flow when needed (a headless app still needs a transient activity to request a
dangerous permission). It shows no persistent UI.

---

## 7. Lifecycle & boot

- **Manual/agent start:** `StarterActivity` or an explicit intent starts
  `McpForegroundService` (foreground, type `specialUse`), which starts the
  engine and the Ktor server.
- **Boot start:** `BootReceiver` on `BOOT_COMPLETED`. Per C5, a `specialUse`
  FGS cannot be started directly from boot on stock/GrapheneOS. Strategy:
  1. If the app is **battery-exempted** (C6), promote to the FGS at boot.
  2. Otherwise defer: schedule a `WorkManager`/alarm that starts the service
     when the app next enters an allowed state, and report "awaiting start" in
     the notification.
- **Battery exemption:** documented user step
  (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` or Settings), required for reliable
  always-on behavior. On a ROM that privileges the app (§9), this is automatic.
- **Stop:** service stop → `portmux_stop` → engine destroyed → requests
  released.

---

## 8. AppFunctions seam (future, API 37 native)

Android 17's **AppFunctions** lets an app act as an on-device MCP source for the
system agent (Gemini). v0.1 does **not** implement it, but the design keeps the
seam:

- All tool logic lives in `PortmuxController`, not in the HTTP layer.
- A future `PortmuxAppFunctions` class annotates thin wrappers
  (`@AppFunction`) that delegate to the same controller.
- No refactor required to add it — it's an additional front end over the same
  controller, alongside the Streamable HTTP server.

---

## 9. Deployment models

`switch-mcp` is built to fill **both** of the following roles from a single
codebase (see §1 "Dual purpose"); the app detects its role at runtime and
adapts its lifecycle (§7).

1. **Normal app (v0.1 verified path):** installed like any APK, iconless,
   user grants Network (INTERNET) + battery exemption. Works on GrapheneOS and
   stock within FGS/boot limits (C5/C6). This is what v0.1 targets and what can
   be tested on the current device (C7).
2. **System-privileged app (co-equal purpose; needs custom OS):** on an XNet /
   GrapheneOS-derived build that places the app in `priv-app` (platform-signed,
   privileged-permission allowlist), boot-start and background limits lift and
   it runs unattended like a system service. The app ships the manifest/
   allowlist hooks for this role as a first-class concern, but the normal-app
   role must not *depend* on it. This path is **not** verifiable on a normal
   locked GrapheneOS device (C7), so it is out of automated verification scope
   for v0.1 — but it is an intended, supported deployment, not a mere footnote.

### Runtime role detection

The app determines its role at startup (e.g. via its install location /
`ApplicationInfo` privileged flag and which privileged permissions were
actually granted) and uses it to decide boot-start strategy (direct FGS promote
vs. deferred, §7) and whether privileged-only capabilities are available. The
MCP `status` tool SHOULD report the detected role so a client can reason about
expected lifecycle behavior.

---

## 10. Dependencies & sourcing (pulls from our repos)

- **Engine:** portmux Android static libs from the
  [`portmux` release](https://github.com/XNet-NGO/portmux/releases)
  (`libportmux.a` + `libhev-task-system.a` per ABI, API 21, `-fPIC`), fetched at
  build time (same mechanism as Switchboard).
- **JNI shim:** adapted from Switchboard's `switchboard-jni.c`.
- **MCP server:** `io.modelcontextprotocol:kotlin-sdk-server` (0.15.x) on Ktor
  (JVM artifact; runs on Android), per `portmux-mcp`.
- **Controller/tools:** modeled on `portmux-mcp`'s `PortmuxEngine` + tool
  registry.

ABIs: arm64-v8a (primary), plus armeabi-v7a / x86_64 / x86 as portmux ships.

---

## 11. Build & toolchain

- Kotlin + AGP (match Switchboard: AGP 9.x, Kotlin 2.4.x), `compileSdk`/
  `targetSdk` 37, `minSdk` 26+ (headless FGS + modern APIs; final floor TBD —
  Compose not required since headless).
- NDK r28 for the JNI `.so`; links portmux's prebuilt `.a`.
- Gradle fetch task pulls the portmux release artifacts into the native build.

---

## 12. Security considerations

- The MCP endpoint creates arbitrary `listen -> target` forwards — a powerful
  capability. Mitigated by: **loopback-only bind**, **loopback-only targets by
  default**, and no auth being acceptable *only because* it is not
  network-reachable. If a future version binds non-loopback, it MUST add
  bearer-token auth (the SDK supports it).
- Headless + boot + background is the shape of abusive software; Play
  Protect/OEMs may flag it. Acceptable for XNet-controlled deployment; a
  consideration for any wider distribution.
- INTERNET denial is fail-safe (no traffic), not fail-silent (state reported).

---

## 13. Acceptance criteria (v0.1)

1. Installs as an **iconless** app on GrapheneOS API 37.
2. Service starts (manually/intent) and the embedded engine loads (native lib
   from the APK, no separate install).
3. MCP client over Streamable HTTP (via `adb forward`) can `initialize`,
   `tools/list` (9 tools), `add_route` (loopback), `start`, forward a real TCP
   connection through the embedded engine, and `stop`.
4. UDP route forwards.
5. With INTERNET **revoked**, the server does not crash and `status` +
   notification report "Network permission denied"; on **re-grant**, forwarding
   works without reinstall.
6. Non-loopback target is rejected unless `ALLOW_ANY_TARGET` is set, and then
   requires `ACCESS_LOCAL_NETWORK`.
7. Clean stop closes the listener and releases resources.

### Explicitly out of verification scope for v0.1

- Boot-start reliability across OEM power managers (documented, not guaranteed).
- Privileged/system-app deployment (needs custom OS, §9/C7).
- Remote (non-loopback) reachability.
- AppFunctions (seam only).
