/*
 ============================================================================
 Name        : switch-mcp-jni.c
 Description : JNI bridge from the switch-mcp Android app to libportmux.
 *
 * Adapted from Switchboard's switchboard-jni.c. Exposes to Kotlin
 * (org.xnet.switchmcp.NativePortmuxEngine) as INSTANCE methods:
 *
 *   nativeNew()                                                      -> long
 *   nativeAddRoute(h, proto, la, lp, ta, tp, egressKind, egressArg,
 *                  networkHandle, token)                             -> int
 *   nativeAddSocks(h, la, lp, egressKind, egressArg,
 *                  networkHandle, token)                             -> int
 *   nativeUpdateNetworkHandle(token, netHandle)
 *   nativeRun(h)                                     (BLOCKS - worker thread)
 *   nativeStop(h)
 *   nativeDestroy(h)
 *
 * portmux_run() blocks and the hev-task scheduler is thread-local, so Kotlin
 * calls nativeRun on a dedicated thread, after all addRoute calls.
 *
 * Egress CALLBACK (no-root per-network pinning) is handled entirely in native
 * code via android_setsocknetwork() from libandroid (NDK). The networkHandle
 * is Network.getNetworkHandle(), the net_handle_t that call expects. This
 * avoids a JNI up-call from the portmux worker thread (which crashes with an
 * invalid JNI transition frame). The handle is LIVE, not frozen at add time:
 * a long-lived SOCKS proxy opens many upstream connections and the pinned
 * Network can be lost/regained (with a different handle) mid-session, so
 * bind_hook reads it atomically per connection and NetworkRequester refreshes
 * it via nativeUpdateNetworkHandle on onAvailable/onLost. A handle of 0 for a
 * pinned route fails CLOSED (no leak onto the default network).
 ============================================================================
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <android/log.h>
#include <android/multinetwork.h>

#include "portmux.h"
#include "portmux-tunnel.h"

#define TAG "switch-mcp-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

/* Per-route CALLBACK context. network_handle is LIVE (atomic); token groups
   contexts that share a transport so an out-of-band update reaches them all. */
typedef struct {
    _Atomic net_handle_t network_handle; /* live; 0 = unavailable (fail closed) */
    int token;                           /* transport group, for live updates */
} EgressCtx;

/* Registry of live CALLBACK contexts. Built on the worker thread at add time,
   read/updated from the ConnectivityManager callback thread. Contexts live for
   the whole forwarder run (intentionally leaked at process exit); the registry
   is reset wholesale when a new engine instance is created. */
#define MAX_EGRESS_CTX 128
static pthread_mutex_t g_ctx_lock = PTHREAD_MUTEX_INITIALIZER;
static EgressCtx *g_ctxs[MAX_EGRESS_CTX];
static int g_ctx_count;

static void
ctx_registry_reset (void)
{
    pthread_mutex_lock (&g_ctx_lock);
    g_ctx_count = 0;
    memset (g_ctxs, 0, sizeof g_ctxs);
    pthread_mutex_unlock (&g_ctx_lock);
}

static void
ctx_registry_add (EgressCtx *ctx)
{
    pthread_mutex_lock (&g_ctx_lock);
    if (g_ctx_count < MAX_EGRESS_CTX)
        g_ctxs[g_ctx_count++] = ctx;
    else
        LOGW ("egress ctx registry full (%d); live updates will not propagate",
              MAX_EGRESS_CTX);
    pthread_mutex_unlock (&g_ctx_lock);
}

static void
ctx_registry_update (int token, net_handle_t netHandle)
{
    pthread_mutex_lock (&g_ctx_lock);
    for (int i = 0; i < g_ctx_count; i++)
        if (g_ctxs[i] && g_ctxs[i]->token == token)
            atomic_store (&g_ctxs[i]->network_handle, netHandle);
    pthread_mutex_unlock (&g_ctx_lock);
}

/* Called by portmux on a freshly created upstream socket (before connect).
   Pins the fd to the LIVE Android Network, in pure native code. Fails closed
   when a pinned route's handle is currently 0. */
static int
bind_hook (int fd, void *user_data)
{
    EgressCtx *ctx = (EgressCtx *)user_data;
    if (!ctx)
        return 0;

    net_handle_t h = atomic_load (&ctx->network_handle);
    if (h == 0) {
        if (ctx->token > 0) {
            LOGW ("bind_hook: network for token=%d unavailable; failing closed "
                  "(fd=%d)", ctx->token, fd);
            return -1;
        }
        return 0; /* token 0 == no pin requested -> default ok */
    }

    int rc = android_setsocknetwork (h, fd);
    if (rc != 0) {
        LOGW ("bind_hook: android_setsocknetwork(handle=%llu, fd=%d) failed: %s",
              (unsigned long long)h, fd, strerror (errno));
        return -1;
    }
    LOGI ("bind_hook: fd=%d pinned to network handle=%llu (token=%d)", fd,
          (unsigned long long)h, ctx->token);
    return 0;
}

static const char *
jstr (JNIEnv *env, jstring s, char *buf, size_t n)
{
    if (!s) { buf[0] = '\0'; return buf; }
    const char *c = (*env)->GetStringUTFChars (env, s, NULL);
    if (!c) { buf[0] = '\0'; return buf; }
    strncpy (buf, c, n - 1);
    buf[n - 1] = '\0';
    (*env)->ReleaseStringUTFChars (env, s, c);
    return buf;
}

/* Build a PortmuxEgress from the shared egress surface. *egp points at @eg
   (populated) or is NULL for PORTMUX_EGRESS_NONE. portmux keeps our string/ctx
   pointers by reference, so these small allocations are intentionally kept for
   the life of the forwarder.
   egressKind: 0 NONE, 1 SOURCE (arg=ip), 2 DEVICE (arg=ifname),
               3 CALLBACK (networkHandle carries the Network; token groups it). */
static void
build_egress (jint egressKind, const char *arg, jlong networkHandle,
              jint token, PortmuxEgress *eg, PortmuxEgress **egp)
{
    memset (eg, 0, sizeof *eg);

    switch (egressKind) {
    case 1:
        eg->kind = PORTMUX_EGRESS_SOURCE;
        eg->source_ip = strdup (arg);
        *egp = eg;
        break;
    case 2:
        eg->kind = PORTMUX_EGRESS_DEVICE;
        eg->device = strdup (arg);
        *egp = eg;
        break;
    case 3: {
        EgressCtx *ctx = calloc (1, sizeof (EgressCtx));
        atomic_init (&ctx->network_handle, (net_handle_t)networkHandle);
        ctx->token = token;
        ctx_registry_add (ctx);
        eg->kind = PORTMUX_EGRESS_CALLBACK;
        eg->callback = bind_hook;
        eg->callback_data = ctx;
        *egp = eg;
        break;
    }
    default:
        *egp = NULL; /* NONE */
    }
}

JNIEXPORT jlong JNICALL
Java_org_xnet_switchmcp_NativePortmuxEngine_nativeNew (JNIEnv *env, jobject o)
{
    (void)env; (void)o;
    ctx_registry_reset ();
    return (jlong)(intptr_t)portmux_new ();
}

JNIEXPORT jint JNICALL
Java_org_xnet_switchmcp_NativePortmuxEngine_nativeAddRoute (
    JNIEnv *env, jobject o, jlong h, jint proto,
    jstring listenAddr, jint listenPort,
    jstring targetAddr, jint targetPort,
    jint egressKind, jstring egressArg, jlong networkHandle, jint token)
{
    (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (!pm) return -1;

    char la[128], ta[128], arg[128];
    jstr (env, listenAddr, la, sizeof la);
    jstr (env, targetAddr, ta, sizeof ta);
    jstr (env, egressArg, arg, sizeof arg);

    PortmuxEgress eg;
    PortmuxEgress *egp = NULL;
    build_egress (egressKind, arg, networkHandle, token, &eg, &egp);

    PortmuxProto p = (proto == 1) ? PORTMUX_PROTO_UDP : PORTMUX_PROTO_TCP;
    int rc = portmux_add_route (pm, p,
                                la[0] ? la : "127.0.0.1", (int)listenPort,
                                ta[0] ? ta : "127.0.0.1", (int)targetPort,
                                egp);
    LOGI ("add_route rc=%d proto=%d %s:%d -> %s:%d egress=%d token=%d",
          rc, (int)proto, la, (int)listenPort, ta, (int)targetPort,
          (int)egressKind, (int)token);
    return rc;
}

/* SOCKS5 proxy listener. No fixed target; every upstream socket the proxy opens
   passes through this egress, so per-connection egress pinning applies. */
JNIEXPORT jint JNICALL
Java_org_xnet_switchmcp_NativePortmuxEngine_nativeAddSocks (
    JNIEnv *env, jobject o, jlong h,
    jstring listenAddr, jint listenPort,
    jint egressKind, jstring egressArg, jlong networkHandle, jint token)
{
    (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (!pm) return -1;

    char la[128], arg[128];
    jstr (env, listenAddr, la, sizeof la);
    jstr (env, egressArg, arg, sizeof arg);

    PortmuxEgress eg;
    PortmuxEgress *egp = NULL;
    build_egress (egressKind, arg, networkHandle, token, &eg, &egp);

    int rc = portmux_add_socks (pm, la[0] ? la : "127.0.0.1", (int)listenPort, egp);
    LOGI ("add_socks rc=%d socks5 %s:%d -> (per-connection) egress=%d token=%d",
          rc, la, (int)listenPort, (int)egressKind, (int)token);
    return rc;
}

/* Push a live network-handle update for every CALLBACK route sharing @token.
   netHandle==0 marks the transport gone; bind_hook then fails closed until a
   fresh handle arrives. Called from the ConnectivityManager callback thread. */
JNIEXPORT void JNICALL
Java_org_xnet_switchmcp_NativePortmuxEngine_nativeUpdateNetworkHandle (
    JNIEnv *env, jobject o, jint token, jlong netHandle)
{
    (void)env; (void)o;
    ctx_registry_update ((int)token, (net_handle_t)netHandle);
    LOGI ("nativeUpdateNetworkHandle: token=%d handle=%llu", (int)token,
          (unsigned long long)netHandle);
}

JNIEXPORT void JNICALL
Java_org_xnet_switchmcp_NativePortmuxEngine_nativeRun (JNIEnv *env, jobject o, jlong h)
{
    (void)env; (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (pm) portmux_run (pm); /* blocks until stop */
}

JNIEXPORT void JNICALL
Java_org_xnet_switchmcp_NativePortmuxEngine_nativeStop (JNIEnv *env, jobject o, jlong h)
{
    (void)env; (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (pm) portmux_stop (pm);
}

JNIEXPORT void JNICALL
Java_org_xnet_switchmcp_NativePortmuxEngine_nativeDestroy (JNIEnv *env, jobject o, jlong h)
{
    (void)env; (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (pm) portmux_destroy (pm);
}

/* Parse a 64-hex-char string into 32 PSK bytes. Returns 0 on success. */
static int
parse_psk_hex (const char *hex, unsigned char out[PM_TUNNEL_PSK_LEN])
{
    if (!hex) return -1;
    size_t n = strlen (hex);
    if (n != PM_TUNNEL_PSK_LEN * 2) return -1;
    for (int i = 0; i < PM_TUNNEL_PSK_LEN; i++) {
        unsigned v;
        if (sscanf (hex + i * 2, "%2x", &v) != 1) return -1;
        out[i] = (unsigned char)v;
    }
    return 0;
}

/* Encrypted tunnel CLIENT: dial serverAddr:serverPort over NoisePSK_NN and
   splice to targetAddr:targetPort (e.g. 127.0.0.1:5555 adbd). pskHex is 64 hex
   chars (32 bytes). cipher: 0=AES-GCM, 1=ChaCha20-Poly1305. The outbound leg
   honors the egress (NETWORK pin etc.), reusing the same egress surface.
   
   REVERSE-FORWARD (v0.4.0): rlistenAddr/rlistenPort open a REVERSE LISTENER on
   the client (when rlistenPort>0): connections there become client-originated
   reverse streams the server splices to rtargetAddr:rtargetPort. rproto sets the
   reverse listener protocol: 0=TCP, 1=UDP, 2=BOTH (dual-stack TCP+UDP). */
JNIEXPORT jint JNICALL
Java_org_xnet_switchmcp_NativePortmuxEngine_nativeAddTunnelClient (
    JNIEnv *env, jobject o, jlong h,
    jstring serverAddr, jint serverPort,
    jstring targetAddr, jint targetPort,
    jstring pskHex, jint cipher,
    jint egressKind, jstring egressArg, jlong networkHandle, jint token,
    jstring rlistenAddr, jint rlistenPort,
    jstring rtargetAddr, jint rtargetPort, jint rproto)
{
    (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (!pm) return -1;

    char sa[128], ta[128], arg[128], pk[128];
    char rla[128], rta[128];
    jstr (env, serverAddr, sa, sizeof sa);
    jstr (env, targetAddr, ta, sizeof ta);
    jstr (env, egressArg, arg, sizeof arg);
    jstr (env, pskHex, pk, sizeof pk);
    jstr (env, rlistenAddr, rla, sizeof rla);
    jstr (env, rtargetAddr, rta, sizeof rta);

    unsigned char psk[PM_TUNNEL_PSK_LEN];
    if (parse_psk_hex (pk, psk) < 0) {
        LOGW ("add_tunnel: bad PSK (need 64 hex chars)");
        return -1;
    }

    PortmuxEgress eg;
    PortmuxEgress *egp = NULL;
    build_egress (egressKind, arg, networkHandle, token, &eg, &egp);

    PmTunnelCipher cph = (cipher == 1) ? PM_TUNNEL_CIPHER_CHACHAPOLY
                                       : PM_TUNNEL_CIPHER_AESGCM;
    
    /* Pass rlistenAddr/rlistenPort as the client listen_addr/listen_port when
       rlistenPort>0 (else NULL,0 for traditional forward-only tunnel). */
    const char *clis_addr = (rlistenPort > 0 && rla[0]) ? rla : NULL;
    int clis_port = (rlistenPort > 0) ? (int)rlistenPort : 0;
    
    int rc = portmux_add_tunnel_client (pm, clis_addr, clis_port,
                                        sa, (int)serverPort,
                                        ta[0] ? ta : "127.0.0.1", (int)targetPort,
                                        psk, cph, egp);
    
    /* Configure reverse target and protocol if a reverse listener was requested. */
    if (rc == 0 && rtargetPort > 0) {
        int r2 = portmux_tunnel_set_reverse_target (pm, rta[0] ? rta : "127.0.0.1",
                                                    (int)rtargetPort);
        if (r2 != 0) {
            LOGW ("add_tunnel: portmux_tunnel_set_reverse_target failed: %d", r2);
            rc = r2;
        }
    }
    if (rc == 0 && rlistenPort > 0) {
        int r3 = portmux_tunnel_set_reverse_proto (pm, (int)rproto);
        if (r3 != 0) {
            LOGW ("add_tunnel: portmux_tunnel_set_reverse_proto failed: %d", r3);
            rc = r3;
        }
    }
    
    /* wipe the local PSK copy */
    memset (psk, 0, sizeof psk);
    LOGI ("add_tunnel rc=%d server=%s:%d target=%s:%d cipher=%d token=%d "
          "reverse=%s:%d->%s:%d proto=%d",
          rc, sa, (int)serverPort, ta, (int)targetPort, (int)cipher, (int)token,
          rla, (int)rlistenPort, rta, (int)rtargetPort, (int)rproto);
    return rc;
}

/* Encrypted tunnel PEER (v0.5.0): a single-port peek-demux tunnel server that
   relays OPENJ multi-hop streams to the next hop (honoring egress) or connects
   the terminal target when no hops remain. Accepts encrypted carriers on
   listenAddr:listenPort. Shares the single psk/cipher with every peer in the
   chain. cipher: 0=AES-GCM, 1=ChaCha20-Poly1305. */
JNIEXPORT jint JNICALL
Java_org_xnet_switchmcp_NativePortmuxEngine_nativeAddTunnelPeer (
    JNIEnv *env, jobject o, jlong h,
    jstring listenAddr, jint listenPort,
    jstring pskHex, jint cipher,
    jint egressKind, jstring egressArg, jlong networkHandle, jint token)
{
    (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (!pm) return -1;

    char la[128], arg[128], pk[128];
    jstr (env, listenAddr, la, sizeof la);
    jstr (env, egressArg, arg, sizeof arg);
    jstr (env, pskHex, pk, sizeof pk);

    unsigned char psk[PM_TUNNEL_PSK_LEN];
    if (parse_psk_hex (pk, psk) < 0) {
        LOGW ("add_peer: bad PSK (need 64 hex chars)");
        return -1;
    }

    PortmuxEgress eg;
    PortmuxEgress *egp = NULL;
    build_egress (egressKind, arg, networkHandle, token, &eg, &egp);

    PmTunnelCipher cph = (cipher == 1) ? PM_TUNNEL_CIPHER_CHACHAPOLY
                                       : PM_TUNNEL_CIPHER_AESGCM;

    int rc = portmux_add_tunnel_peer (pm, la[0] ? la : "127.0.0.1",
                                      (int)listenPort, psk, cph, egp);

    /* wipe the local PSK copy */
    memset (psk, 0, sizeof psk);
    LOGI ("add_peer rc=%d listen=%s:%d cipher=%d egress=%d token=%d",
          rc, la, (int)listenPort, (int)cipher, (int)egressKind, (int)token);
    return rc;
}

/* Parse a decimal int from [s, end); returns the value, defaulting to 0 on an
   empty/invalid field. */
static int
parse_int_field (const char *s, size_t len)
{
    char tmp[16];
    size_t n = len < sizeof tmp - 1 ? len : sizeof tmp - 1;
    memcpy (tmp, s, n);
    tmp[n] = '\0';
    return (int)strtol (tmp, NULL, 10);
}

/* Multi-hop jumps (v0.5.0): configure the EDGE originator's jump-list on the
   most-recently-added tunnel route (call right after the edge
   nativeAddTunnelClient). hopsAddrCsv / hopsPortCsv are comma-separated lists
   (same arity) of the ordered next-hops; hops[0] MUST equal that client's
   carrier dest. targetAddr:targetPort is the final destination; proto is the
   final leg (0=TCP, 1=UDP). nhops must be 1..PM_MUX_MAX_HOPS. */
JNIEXPORT jint JNICALL
Java_org_xnet_switchmcp_NativePortmuxEngine_nativeSetJumps (
    JNIEnv *env, jobject o, jlong h,
    jstring hopsAddrCsv, jstring hopsPortCsv,
    jstring targetAddr, jint targetPort, jint proto)
{
    (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (!pm) return -1;

    char addrs[512], ports[256], ta[128];
    jstr (env, hopsAddrCsv, addrs, sizeof addrs);
    jstr (env, hopsPortCsv, ports, sizeof ports);
    jstr (env, targetAddr, ta, sizeof ta);

    PmJumpEndpoint hops[PM_MUX_MAX_HOPS];
    memset (hops, 0, sizeof hops);
    int nhops = 0;

    /* Walk the two CSVs in lockstep. */
    const char *ap = addrs;
    const char *pp = ports;
    while (*ap && nhops < PM_MUX_MAX_HOPS) {
        const char *acomma = strchr (ap, ',');
        size_t alen = acomma ? (size_t)(acomma - ap) : strlen (ap);
        if (alen == 0) break;
        size_t acopy = alen < sizeof hops[nhops].addr - 1
                       ? alen : sizeof hops[nhops].addr - 1;
        memcpy (hops[nhops].addr, ap, acopy);
        hops[nhops].addr[acopy] = '\0';

        const char *pcomma = pp ? strchr (pp, ',') : NULL;
        size_t plen = pp ? (pcomma ? (size_t)(pcomma - pp) : strlen (pp)) : 0;
        hops[nhops].port = pp ? parse_int_field (pp, plen) : 0;

        nhops++;
        ap = acomma ? acomma + 1 : ap + alen;
        if (pp) pp = pcomma ? pcomma + 1 : pp + plen;
    }

    if (nhops < 1) {
        LOGW ("set_jumps: no hops parsed");
        return -1;
    }

    int rc = portmux_tunnel_set_jumps (pm, hops, nhops,
                                       ta[0] ? ta : "127.0.0.1",
                                       (int)targetPort, (int)proto);
    LOGI ("set_jumps rc=%d nhops=%d hop0=%s:%d target=%s:%d proto=%d",
          rc, nhops, hops[0].addr, hops[0].port, ta, (int)targetPort,
          (int)proto);
    return rc;
}
