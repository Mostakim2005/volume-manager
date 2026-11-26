package com.volumemanager

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.volumemanager.util.CrashReporter
import com.volumemanager.util.SystemVolumeHelper

/**
 * Quick Settings tile providing a 1-tap trigger to summon Realme UI's native
 * multi-stream volume dialog with zero background battery cost.
 */
@RequiresApi(Build.VERSION_CODES.N)
class VolumeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_ACTIVE
        tile.label = getString(R.string.qs_tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(R.string.qs_tile_subtitle)
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        CrashReporter.logEvent(this, "TILE", "QS tile clicked")

        val launchIntent = Intent(this, PanelLaunchActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    21,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(launchIntent)
            }
        } catch (e: Throwable) {
            CrashReporter.recordError(this, "VolumeTileService.onClick", e)
            SystemVolumeHelper.showSystemVolumeUi(this)
        }
    }
}
