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
   honors the egress (NETWORK pin etc.), reusing the same egress surface. */
JNIEXPORT jint JNICALL
Java_org_xnet_switchmcp_NativePortmuxEngine_nativeAddTunnelClient (
    JNIEnv *env, jobject o, jlong h,
    jstring serverAddr, jint serverPort,
    jstring targetAddr, jint targetPort,
    jstring pskHex, jint cipher,
    jint egressKind, jstring egressArg, jlong networkHandle, jint token)
{
    (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (!pm) return -1;

    char sa[128], ta[128], arg[128], pk[128];
    jstr (env, serverAddr, sa, sizeof sa);
    jstr (env, targetAddr, ta, sizeof ta);
    jstr (env, egressArg, arg, sizeof arg);
    jstr (env, pskHex, pk, sizeof pk);

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
    int rc = portmux_add_tunnel_client (pm, NULL, 0,
                                        sa, (int)serverPort,
                                        ta[0] ? ta : "127.0.0.1", (int)targetPort,
                                        psk, cph, egp);
    /* wipe the local PSK copy */
    memset (psk, 0, sizeof psk);
    LOGI ("add_tunnel rc=%d server=%s:%d target=%s:%d cipher=%d token=%d",
          rc, sa, (int)serverPort, ta, (int)targetPort, (int)cipher, (int)token);
    return rc;
}
