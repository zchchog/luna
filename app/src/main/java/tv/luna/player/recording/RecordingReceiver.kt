package tv.luna.player.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tv.luna.player.IptvApp

/** The 1-minute warning and the start of a scheduled recording (alarms are set again on boot/update by BootReceiver). */
class RecordingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as IptvApp).container
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_WARN -> c.recordings.onWarn(id)
                    ACTION_START -> c.recordings.onStart(id)
                }
            } catch (e: Exception) {
                Log.w("IptvRecord", "recording alarm ${intent.action} failed: $e") // never crash the process from an alarm
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_WARN = "tv.luna.player.recording.WARN"
        const val ACTION_START = "tv.luna.player.recording.START"
        const val EXTRA_ID = "id"
    }
}
