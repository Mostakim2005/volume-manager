package com.volumemanager

import android.app.Application
import com.volumemanager.util.CrashReporter

/**
 * Installs the offline crash & diagnostic reporter before any Activity,
 * Service, or BroadcastReceiver executes.
 */
class VolumeManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
