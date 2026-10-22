package org.xnet.switchmcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the embedded portmux engine and the Ktor MCP server for the app's active
 * lifetime (SPEC §3/§7). Foreground, type specialUse (§5 manifest). Posts the
 * ongoing notification that is the only always-visible status surface for this
 * headless app (§6) — it must surface "Network permission denied" (C1/C2).
 *
 * Fail-safe network handling (SPEC §5/§12, acceptance #5): INTERNET is
 * revocable on GrapheneOS (C2) and without it the kernel tears down the uid's
 * sockets (C1), so even binding the loopback MCP listener throws. The server
 * MUST NOT crash: a supervising coroutine attempts the bind, catches any
 * failure, flips the notification to "Network permission denied", and keeps
 * retrying until a grant lets the bind succeed. A last-resort uncaught-exception
 * handler is installed so a socket failure raised on a Ktor worker thread (the
 * CIO accept loop binds lazily, off the synchronous start path) can never take
 * the process down.
 */
class McpForegroundService : Service() {

    private var server: EmbeddedServer<*, *>? = null
    private var controller: PortmuxController? = null
    private var scope: CoroutineScope? = null
    private var supervisor: Job? = null

    @Volatile private var port: Int = DEFAULT_PORT
    @Volatile private var serverListening: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        installUncaughtHandler()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "stop requested")
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }

        port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        val allowAnyTarget = intent?.getBooleanExtra(EXTRA_ALLOW_ANY_TARGET, false) ?: false

        val ctrl = controller ?: PortmuxController(
            context = applicationContext,
            allowAnyTarget = allowAnyTarget,
        ).also { controller = it }

        createChannel()
        startForegroundCompat(buildNotification(ctrl))

        if (supervisor?.isActive != true) {
            startSupervisor(ctrl)
        }
        return START_STICKY
    }

    /**
     * Supervises the Ktor server: (re)bind when network is available, back off
     * and keep the "denied" notification up when it is not. Never throws out of
     * the service lifecycle.
     */
    private fun startSupervisor(ctrl: PortmuxController) {
        val s = scope ?: return
        supervisor = s.launch {
            var backoffMs = 1_000L
            while (isActive) {
                val net = ctrl.networkState()
                if (!serverListening) {
                    if (net.granted || net.declared) {
                        // Attempt the bind. If INTERNET is actually revoked the
                        // bind throws SocketException("Operation not permitted");
                        // we catch it and treat the server as not listening.
                        val startedOk = tryStartServer(ctrl)
                        if (startedOk) {
                            serverListening = true
                            backoffMs = 1_000L
                            Log.i(TAG, "MCP server listening on http://$HOST:$port/mcp")
                        } else {
                            Log.w(TAG, "server bind failed (Network likely denied); retrying in ${backoffMs}ms")
                        }
                    }
                }
                refreshNotification(ctrl)
                if (serverListening) {
                    // Steady state: poll network occasionally so the notification
                    // and status stay accurate, and so we notice a teardown.
                    delay(5_000L)
                    if (!isServerHealthy()) {
                        Log.w(TAG, "server no longer healthy (socket likely torn down); will rebind")
                        teardownServer()
                        serverListening = false
                    }
                } else {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    /** Returns true if the server bound successfully, false on a bind failure. */
    private fun tryStartServer(ctrl: PortmuxController): Boolean {
        return try {
            val srv = embeddedServer(CIO, host = HOST, port = port) {
                mcpStreamableHttp { buildSwitchMcpServer(ctrl) }
            }
            srv.start(wait = false)
            // CIO binds lazily on an accept coroutine; give it a moment and
            // verify the engine is actually accepting (not failed to bind).
            server = srv
            true
        } catch (t: Throwable) {
            Log.w(TAG, "tryStartServer failed: ${t.javaClass.simpleName}: ${t.message}")
            try {
                server?.stop(100, 200)
            } catch (_: Throwable) {
            }
            server = null
            false
        }
    }

    private fun isServerHealthy(): Boolean {
        // The CIO engine stays up object-wise even if a bind failed on its
        // worker; probe the actual listener by opening a loopback socket.
        return try {
            java.net.Socket().use { sock ->
                sock.connect(java.net.InetSocketAddress(HOST, port), 500)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun teardownServer() {
        try {
            server?.stop(200, 500)
        } catch (t: Throwable) {
            Log.w(TAG, "teardown error: ${t.message}")
        }
        server = null
    }

    private fun stopEverything() {
        supervisor?.cancel()
        supervisor = null
        try {
            controller?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "engine stop error: ${t.message}")
        }
        teardownServer()
        serverListening = false
    }

    override fun onDestroy() {
        stopEverything()
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    private fun installUncaughtHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            // A revoked INTERNET permission makes socket operations on Ktor's
            // accept/IO worker throw asynchronously (C1). That must not crash
            // the process — log and swallow socket-level failures; the
            // supervisor rebinds when the grant returns. Delegate anything else
            // to the previous handler.
            val isSocket = ex is java.net.SocketException ||
                ex is java.io.IOException ||
                (ex.cause is java.net.SocketException)
            if (isSocket) {
                Log.w(TAG, "swallowed socket-level failure on ${thread.name}: ${ex.message} (Network likely revoked, C1)")
                serverListening = false
            } else {
                Log.e(TAG, "uncaught on ${thread.name}", ex)
                prev?.uncaughtException(thread, ex)
            }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "switch-mcp status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "MCP forwarder status" }
        nm.createNotificationChannel(channel)
    }

    private fun refreshNotification(ctrl: PortmuxController) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(ctrl))
    }

    private fun buildNotification(ctrl: PortmuxController): Notification {
        val status = ctrl.status()
        val title = if (status.network.granted) {
            "switch-mcp running"
        } else {
            "switch-mcp: Network permission denied"
        }
        val text = buildString {
            append("http://$HOST:$port/mcp")
            append(" · ").append(if (serverListening) "listening" else "awaiting network")
            append(" · ").append(if (status.running) "forwarding" else "idle")
            append(" · ").append(status.routeCount).append(" route(s)")
            append(" · ").append(status.role.name.lowercase())
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // stock icon; app is iconless
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "switch-mcp"
        private const val CHANNEL_ID = "switch-mcp"
        private const val NOTIF_ID = 1
        const val HOST = "127.0.0.1"
        const val DEFAULT_PORT = 3339

        const val ACTION_START = "org.xnet.switchmcp.action.START"
        const val ACTION_STOP = "org.xnet.switchmcp.action.STOP"
        const val EXTRA_PORT = "org.xnet.switchmcp.extra.PORT"
        const val EXTRA_ALLOW_ANY_TARGET = "org.xnet.switchmcp.extra.ALLOW_ANY_TARGET"

        fun startIntent(context: Context, port: Int = DEFAULT_PORT, allowAnyTarget: Boolean = false): Intent =
            Intent(context, McpForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_ALLOW_ANY_TARGET, allowAnyTarget)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, McpForegroundService::class.java).apply { action = ACTION_STOP }
    }
}
