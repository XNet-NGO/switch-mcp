package org.xnet.switchmcp

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * The deployment role the app is running in (SPEC §1 "Dual purpose", §9).
 *
 *  - [NORMAL]: ordinary user-installed APK, bound by stock/GrapheneOS
 *    FGS/boot/background limits.
 *  - [PRIVILEGED]: the same build placed in `priv-app` (platform-signed, on the
 *    privileged-permission allowlist) on an XNet / GrapheneOS-derived OS, where
 *    boot-start and background limits are lifted.
 *
 * The role is detected at runtime from the install location flags and drives
 * the boot-start strategy (§7) and is reported by the `status` MCP tool.
 */
enum class DeploymentRole {
    NORMAL,
    PRIVILEGED,
    ;

    companion object {
        private const val TAG = "switch-mcp"

        fun detect(context: Context): DeploymentRole {
            val ai = context.applicationInfo
            val flags = ai.flags
            // FLAG_SYSTEM: app in the system image. FLAG_UPDATED_SYSTEM_APP: a
            // system app updated by a user APK. Either indicates a privileged
            // install slot. (priv-app specifically also lands in the system
            // partition, so FLAG_SYSTEM is the practical signal available to a
            // non-privileged API surface.)
            val isSystem =
                (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val role = if (isSystem) PRIVILEGED else NORMAL
            Log.i(TAG, "deployment role detected: $role (flags=0x${flags.toString(16)})")
            return role
        }
    }
}
