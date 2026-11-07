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

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        installUncaughtHandler()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                stopEverything()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                isRunning = false
                SwitchMcpTileService.requestTileUpdate(applicationContext)
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_ALLOW_ANY_TARGET -> {
                val ctrl = controller
                if (ctrl != null) {
                    val newValue = !ctrl.allowAnyTarget
                    ctrl.allowAnyTarget = newValue
                    isAllowAnyTarget = newValue
                    prefs.edit().putBoolean(KEY_ALLOW_ANY_TARGET, newValue).apply()
                    Log.i(TAG, "allowAnyTarget toggled to $newValue via notification action")
                    refreshNotification(ctrl)
                    SwitchMcpTileService.requestTileUpdate(applicationContext)
                } else {
                    Log.w(TAG, "Cannot toggle allowAnyTarget: controller not running")
                }
                return START_STICKY
            }
            ACTION_SET_ALLOW_ANY_TARGET -> {
                val targetVal = intent.getBooleanExtra(EXTRA_ALLOW_ANY_TARGET, false)
                val ctrl = controller
                if (ctrl != null) {
                    ctrl.allowAnyTarget = targetVal
                    isAllowAnyTarget = targetVal
                    prefs.edit().putBoolean(KEY_ALLOW_ANY_TARGET, targetVal).apply()
                    Log.i(TAG, "allowAnyTarget set to $targetVal via intent")
                    refreshNotification(ctrl)
                    SwitchMcpTileService.requestTileUpdate(applicationContext)
                }
                return START_STICKY
            }
        }

        port = intent?.getIntExtra(EXTRA_PORT, currentPort) ?: currentPort
        currentPort = port

        val allowAnyTarget = if (intent?.hasExtra(EXTRA_ALLOW_ANY_TARGET) == true) {
            val extraVal = intent.getBooleanExtra(EXTRA_ALLOW_ANY_TARGET, false)
            prefs.edit().putBoolean(KEY_ALLOW_ANY_TARGET, extraVal).apply()
            extraVal
        } else {
            prefs.getBoolean(KEY_ALLOW_ANY_TARGET, false)
        }
        isAllowAnyTarget = allowAnyTarget
        isRunning = true
        SwitchMcpTileService.requestTileUpdate(applicationContext)

        val ctrl = controller?.also {
            it.allowAnyTarget = allowAnyTarget
        } ?: PortmuxController(
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
        isRunning = false
        SwitchMcpTileService.requestTileUpdate(applicationContext)
    }

    override fun onDestroy() {
        stopEverything()
        scope?.cancel()
        scope = null
        isRunning = false
        SwitchMcpTileService.requestTileUpdate(applicationContext)
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
        val targetScope = if (ctrl.allowAnyTarget) "Any Target" else "Loopback Only"
        val title = if (status.network.granted) {
            "switch-mcp · $HOST:$port [$targetScope]"
        } else {
            "switch-mcp: Network permission denied ($HOST:$port) [$targetScope]"
        }
        val text = buildString {
            append("http://$HOST:$port/mcp")
            append(" · ").append(if (serverListening) "listening" else "awaiting network")
            append(" · ").append(if (status.running) "forwarding" else "idle")
            append(" · ").append(status.routeCount).append(" route(s)")
            append(" · ").append(status.role.name.lowercase())
            if (ctrl.allowAnyTarget) {
                append(" · any-target")
            }
        }

        val togglePendingIntent = android.app.PendingIntent.getService(
            this,
            102,
            toggleAllowAnyTargetIntent(this),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleTitle = if (ctrl.allowAnyTarget) {
            getString(R.string.action_block_any_target)
        } else {
            getString(R.string.action_allow_any_target)
        }
        val toggleIconRes = if (ctrl.allowAnyTarget) {
            android.R.drawable.checkbox_on_background
        } else {
            android.R.drawable.checkbox_off_background
        }
        val toggleAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, toggleIconRes),
            toggleTitle,
            togglePendingIntent,
        ).build()

        val stopPendingIntent = android.app.PendingIntent.getService(
            this,
            101,
            stopIntent(this),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
            getString(R.string.action_stop),
            stopPendingIntent,
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // stock icon; app is iconless
            .setOngoing(true)
            .addAction(toggleAction)
            .addAction(stopAction)
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
        const val PREFS_NAME = "switch_mcp_prefs"
        const val KEY_ALLOW_ANY_TARGET = "allow_any_target"
        const val HOST = "127.0.0.1"
        const val DEFAULT_PORT = 3339

        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var currentPort: Int = DEFAULT_PORT
            private set
        @Volatile var isAllowAnyTarget: Boolean = false
            private set

        const val ACTION_START = "org.xnet.switchmcp.action.START"
        const val ACTION_STOP = "org.xnet.switchmcp.action.STOP"
        const val ACTION_TOGGLE_ALLOW_ANY_TARGET = "org.xnet.switchmcp.action.TOGGLE_ALLOW_ANY_TARGET"
        const val ACTION_SET_ALLOW_ANY_TARGET = "org.xnet.switchmcp.action.SET_ALLOW_ANY_TARGET"
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

        fun toggleAllowAnyTargetIntent(context: Context): Intent =
            Intent(context, McpForegroundService::class.java).apply { action = ACTION_TOGGLE_ALLOW_ANY_TARGET }

        fun setAllowAnyTargetIntent(context: Context, allow: Boolean): Intent =
            Intent(context, McpForegroundService::class.java).apply {
                action = ACTION_SET_ALLOW_ANY_TARGET
                putExtra(EXTRA_ALLOW_ANY_TARGET, allow)
            }
    }
}
