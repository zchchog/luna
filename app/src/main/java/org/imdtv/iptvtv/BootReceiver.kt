package org.imdtv.iptvtv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Start on boot (spec §4.5): Android allows the start only with "Display over other apps" granted. Boot and app updates
 * clear alarms: these broadcasts start the process, and IptvApp.onCreate sets the recording alarms again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // After a one-click update the old app was closed: open the new one (needs "Display over other apps" too).
            // Updating also clears alarms; the process starting is enough for IptvApp.onCreate to set the recording alarms again.
            if (org.imdtv.iptvtv.data.update.reopenMarker(context).delete() && android.provider.Settings.canDrawOverlays(context)) {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
            try {
                val app = context.applicationContext as IptvApp
                // One small DataStore file; goAsync allows ~10 s.
                if (runBlocking { app.container.settings.settings.first().startOnBoot }) {
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (e: Exception) {
                android.util.Log.w("BootReceiver", "start on boot skipped: $e") // never crash the process at boot
            } finally {
                pending.finish()
            }
        }.start()
    }
}
