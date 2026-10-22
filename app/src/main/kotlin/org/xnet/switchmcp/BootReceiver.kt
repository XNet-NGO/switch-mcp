package org.xnet.switchmcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Headless boot start (SPEC §7). Per C5 a `specialUse` FGS cannot be started
 * directly from boot on stock/GrapheneOS without a battery exemption. Strategy:
 *
 *  - PRIVILEGED role (§9): boot-start limits are lifted; promote to the FGS
 *    directly.
 *  - NORMAL role + battery-exempt (C6): promote to the FGS directly.
 *  - NORMAL role, not exempt: defer. v0.1 logs "awaiting start" rather than
 *    scheduling WorkManager (the spec allows either; deferral keeps v0.1
 *    dependency-free and the normal path is a documented manual/agent start).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val role = DeploymentRole.detect(context)
        val exempt = isBatteryExempt(context)
        Log.i(TAG, "boot received: role=$role, batteryExempt=$exempt")

        if (role == DeploymentRole.PRIVILEGED || exempt) {
            Log.i(TAG, "promoting to foreground service at boot")
            try {
                context.startForegroundService(McpForegroundService.startIntent(context))
            } catch (t: Throwable) {
                Log.w(TAG, "boot FGS promote failed: ${t.message}")
            }
        } else {
            Log.i(
                TAG,
                "deferring start: NORMAL role without battery exemption (C5); " +
                    "awaiting manual/agent start or battery exemption",
            )
        }
    }

    private fun isBatteryExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    companion object {
        private const val TAG = "switch-mcp"
    }
}
