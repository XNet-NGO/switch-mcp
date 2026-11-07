package org.xnet.switchmcp

import android.content.ComponentName
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Quick Settings Tile to toggle switch-mcp on and off.
 * Displays current status and active ip:port subtitle.
 */
class SwitchMcpTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        if (McpForegroundService.isRunning) {
            Log.i(TAG, "Tile clicked: stopping switch-mcp")
            startService(McpForegroundService.stopIntent(this))
        } else {
            Log.i(TAG, "Tile clicked: starting switch-mcp")
            startForegroundService(McpForegroundService.startIntent(this))
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val running = McpForegroundService.isRunning
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val scope = if (McpForegroundService.isAllowAnyTarget) " (any)" else ""
            tile.subtitle = if (running) "${McpForegroundService.HOST}:${McpForegroundService.currentPort}$scope" else "Off"
        }
        tile.updateTile()
    }

    companion object {
        private const val TAG = "switch-mcp-tile"

        fun requestTileUpdate(context: android.content.Context) {
            try {
                requestListeningState(
                    context,
                    ComponentName(context, SwitchMcpTileService::class.java),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "requestTileUpdate failed: ${t.message}")
            }
        }
    }
}
