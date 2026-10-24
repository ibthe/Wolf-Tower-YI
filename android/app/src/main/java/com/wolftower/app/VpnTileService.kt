package com.wolftower.app

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Плитка быстрых настроек: включить и выключить защиту без открытия приложения. */
class VpnTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (DpiVpnService.running.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Wolf Tower"
        tile.updateTile()
    }

    override fun onClick() {
        try {
            if (DpiVpnService.running.value) {
                startService(Intent(this, DpiVpnService::class.java).setAction(DpiVpnService.ACTION_STOP))
                return
            }
            val needPermission = VpnService.prepare(this) != null
            val ready = LicenseManager.canRun(this) && Settings.enabledIds(this).isNotEmpty()
            if (needPermission || !ready) {
                openApp()
                return
            }
            startService(Intent(this, DpiVpnService::class.java).setAction(DpiVpnService.ACTION_START))
        } catch (_: Exception) {
            openApp()
        }
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
