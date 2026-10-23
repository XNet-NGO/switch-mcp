/*
 ============================================================================
 Name        : switch-mcp-jni.c
 Description : JNI bridge from the switch-mcp Android app to libportmux.
 *
 * Adapted from Switchboard's switchboard-jni.c. Exposes to Kotlin
 * (org.xnet.switchmcp.NativePortmuxEngine) as INSTANCE methods:
 *
 *   nativeNew()                                                   -> long handle
 *   nativeAddRoute(h, proto, la, lp, ta, tp, egressKind, egressArg) -> int
 *   nativeRun(h)                                     (BLOCKS - worker thread)
 *   nativeStop(h)
 *   nativeDestroy(h)
 *
 * portmux_run() blocks and the hev-task scheduler is thread-local, so Kotlin
 * calls nativeRun on a dedicated thread, after all addRoute calls.
 *
 * v0.1 is loopback-only, so no network pinning (CALLBACK egress) is wired here;
 * the egressKind/egressArg surface is kept for parity with portmux-mcp and to
 * allow SOURCE/DEVICE egress later without changing the Kotlin signature.
 ============================================================================
 */

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <android/log.h>

#include "portmux.h"

#define TAG "switch-mcp-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

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

JNIEXPORT jlong JNICALL
Java_org_xnet_switchmcp_NativePortmuxEngine_nativeNew (JNIEnv *env, jobject o)
{
    (void)env; (void)o;
    return (jlong)(intptr_t)portmux_new ();
}

/* egressKind: 0 NONE, 1 SOURCE (egressArg=ip), 2 DEVICE (egressArg=ifname).
   v0.1 only exercises NONE (loopback). */
JNIEXPORT jint JNICALL
Java_org_xnet_switchmcp_NativePortmuxEngine_nativeAddRoute (
    JNIEnv *env, jobject o, jlong h, jint proto,
    jstring listenAddr, jint listenPort,
    jstring targetAddr, jint targetPort,
    jint egressKind, jstring egressArg)
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
    memset (&eg, 0, sizeof eg);

    switch (egressKind) {
    case 1:
        eg.kind = PORTMUX_EGRESS_SOURCE;
        eg.source_ip = strdup (arg);
        egp = &eg;
        break;
    case 2:
        eg.kind = PORTMUX_EGRESS_DEVICE;
        eg.device = strdup (arg);
        egp = &eg;
        break;
    default:
        egp = NULL; /* NONE */
    }

    PortmuxProto p = (proto == 1) ? PORTMUX_PROTO_UDP : PORTMUX_PROTO_TCP;
    int rc = portmux_add_route (pm, p,
                                la[0] ? la : "127.0.0.1", (int)listenPort,
                                ta[0] ? ta : "127.0.0.1", (int)targetPort,
                                egp);
    /* portmux copies primitive fields but keeps our string pointers by
       reference, so the small strdup is intentionally kept for the life of the
       forwarder (freed implicitly at process exit). Routes are few. */
    LOGI ("add_route rc=%d proto=%d %s:%d -> %s:%d",
          rc, (int)proto, la, (int)listenPort, ta, (int)targetPort);
    return rc;
}

/* SOCKS5 proxy listener. No fixed target: the per-connection destination is
   supplied by the SOCKS client. egressKind mirrors nativeAddRoute
   (0 NONE, 1 SOURCE, 2 DEVICE); every upstream socket the proxy opens passes
   through this egress, so egress pinning is applied per client connection. */
JNIEXPORT jint JNICALL
Java_org_xnet_switchmcp_NativePortmuxEngine_nativeAddSocks (
    JNIEnv *env, jobject o, jlong h,
    jstring listenAddr, jint listenPort,
    jint egressKind, jstring egressArg)
{
    (void)o;
    Portmux *pm = (Portmux *)(intptr_t)h;
    if (!pm) return -1;

    char la[128], arg[128];
    jstr (env, listenAddr, la, sizeof la);
    jstr (env, egressArg, arg, sizeof arg);

    PortmuxEgress eg;
    PortmuxEgress *egp = NULL;
    memset (&eg, 0, sizeof eg);

    switch (egressKind) {
    case 1:
        eg.kind = PORTMUX_EGRESS_SOURCE;
        eg.source_ip = strdup (arg);
        egp = &eg;
        break;
    case 2:
        eg.kind = PORTMUX_EGRESS_DEVICE;
        eg.device = strdup (arg);
        egp = &eg;
        break;
    default:
        egp = NULL; /* NONE */
    }

    int rc = portmux_add_socks (pm,
                                la[0] ? la : "127.0.0.1", (int)listenPort,
                                egp);
    /* As in add_route, portmux keeps our string pointers by reference, so the
       small strdup is intentionally kept for the life of the forwarder. */
    LOGI ("add_socks rc=%d socks5 %s:%d -> (per-connection)",
          rc, la, (int)listenPort);
    return rc;
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
