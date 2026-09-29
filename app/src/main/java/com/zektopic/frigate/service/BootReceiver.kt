package com.zektopic.frigate.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Brings the NVR back after a reboot or an app update.
 *
 * Without this the service only ever started from [com.zektopic.frigate.MainActivity],
 * so a power cut left the tablet silently not recording until someone opened the app
 * (three days, the first time it was noticed). With a secure lock screen Android holds
 * BOOT_COMPLETED until the first unlock, because the app's database lives in
 * credential-encrypted storage, so recording resumes at that unlock rather than at boot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!NvrRunState.shouldRun(context)) {
            Log.i(TAG, "Not starting the NVR after $action: it was stopped from the app")
            return
        }
        Log.i(TAG, "Starting the NVR after $action")
        try {
            ContextCompat.startForegroundService(context, Intent(context, NvrService::class.java))
        } catch (e: Exception) {
            // Both actions are on the list of exemptions from the background start
            // restrictions, so this should not happen; log rather than crash the receiver.
            Log.e(TAG, "Could not start the NVR after $action", e)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}

/**
 * Whether the user wants the NVR running: set when they start it, cleared only when
 * they stop it from the app, so an explicit Stop survives a reboot.
 */
object NvrRunState {
    private const val PREFS = "nvr_run_state"
    private const val KEY_SHOULD_RUN = "should_run"

    fun shouldRun(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOULD_RUN, true)

    fun setShouldRun(context: Context, run: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOULD_RUN, run).apply()
    }
}
