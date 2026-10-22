package org.xnet.switchmcp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Minimal, iconless entry point (SPEC §6). It exists only to (a) be an
 * adb/intent hook to start [McpForegroundService] and (b) host the transient
 * runtime-permission request (POST_NOTIFICATIONS; and ACCESS_LOCAL_NETWORK when
 * ALLOW_ANY_TARGET is used). It shows no persistent UI and finishes immediately.
 *
 * Start from adb:
 *   adb shell am start -n org.xnet.switchmcp/.StarterActivity
 *   adb shell am start -n org.xnet.switchmcp/.StarterActivity --ei port 3339 --ez allow_any_target true
 */
class StarterActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val port = intent?.getIntExtra("port", McpForegroundService.DEFAULT_PORT)
            ?: McpForegroundService.DEFAULT_PORT
        val allowAnyTarget = intent?.getBooleanExtra("allow_any_target", false) ?: false

        requestTransientPermissions(allowAnyTarget)

        Log.i(TAG, "StarterActivity launching service (port=$port, allowAnyTarget=$allowAnyTarget)")
        startForegroundService(
            McpForegroundService.startIntent(this, port = port, allowAnyTarget = allowAnyTarget),
        )

        // Headless: no UI. Finish right away.
        finish()
    }

    private fun requestTransientPermissions(allowAnyTarget: Boolean) {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        // ACCESS_LOCAL_NETWORK is only needed for non-loopback targets (C3).
        val localNet = "android.permission.ACCESS_LOCAL_NETWORK"
        if (allowAnyTarget && !granted(localNet)) {
            wanted += localNet
        }
        if (wanted.isNotEmpty()) {
            try {
                requestPermissions(wanted.toTypedArray(), REQ_PERMS)
            } catch (t: Throwable) {
                Log.w(TAG, "permission request failed: ${t.message}")
            }
        }
    }

    private fun granted(perm: String): Boolean =
        checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "switch-mcp"
        private const val REQ_PERMS = 1
    }
}
