package org.xnet.switchmcp

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Checks INTERNET grant state (SPEC C1/C2). On GrapheneOS the INTERNET
 * permission is a user-revocable `dangerous` permission ("Network" toggle);
 * without it even a loopback listener cannot bind, so switch-mcp must detect
 * the denied state and report it (it must not crash — fail-safe, not
 * fail-silent, §5/§12).
 *
 * There is no public runtime check for "INTERNET granted" the way there is for
 * dangerous runtime permissions, because INTERNET is normally install-time.
 * On GrapheneOS, when the toggle is off the app has no validated network
 * transport, so we approximate the grant state via ConnectivityManager: if the
 * manifest declares INTERNET but no network reports NET_CAPABILITY_INTERNET
 * reachable to us, we surface "likely denied" and keep retrying.
 */
class NetworkGuard(private val context: Context) {

    data class State(
        /** Whether INTERNET is declared in the manifest (always true for us). */
        val declared: Boolean,
        /** Best-effort: whether the app appears to have working network access. */
        val granted: Boolean,
    ) {
        fun describe(): String = when {
            !declared -> "INTERNET not declared"
            granted -> "Network permission granted"
            else -> "Network permission denied"
        }
    }

    fun declared(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.INTERNET) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Best-effort grant probe. INTERNET is install-time so [checkSelfPermission]
     * usually returns GRANTED even when GrapheneOS has revoked network access;
     * we therefore also consult ConnectivityManager for a usable transport.
     */
    fun state(): State {
        val declared = declared()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val hasUsableNetwork = cm?.let {
            val active = it.activeNetwork ?: return@let false
            val caps = it.getNetworkCapabilities(active) ?: return@let false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: false
        // Loopback works whenever the uid's sockets are not torn down, which on
        // GrapheneOS tracks the Network toggle; treat declared + any usable
        // network as granted, declared + none as "denied/unknown".
        val granted = declared && hasUsableNetwork
        val s = State(declared = declared, granted = granted)
        Log.i("switch-mcp", "network state: ${s.describe()} (declared=$declared, usableNet=$hasUsableNetwork)")
        return s
    }
}
