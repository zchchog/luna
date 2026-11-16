package tv.luna.player.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.luna.player.IptvApp
import tv.luna.player.R
import java.io.File

/** Runs the recordings (one job each) in the foreground, with the box kept awake if the setting says so. */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<Long, Job>() // main thread only
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val container get() = (application as IptvApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService needs this within seconds, whatever the request.
        notify(container.recordings.running.value.values.map { it.title })
        val id = intent?.getLongExtra(EXTRA_ID, -1L) ?: -1L
        when (intent?.action) {
            ACTION_START -> if (id >= 0 && id !in jobs) start(id)
            ACTION_STOP -> jobs[id]?.cancel()
        }
        if (jobs.isEmpty()) finishIfIdle()
        return START_NOT_STICKY
    }

    private fun start(id: Long) {
        val repo = container.recordings
        jobs[id] = scope.launch {
            val s = container.settings.settings.first()
            val store = container.recordingStore(s)
            if (store == null) {
                container.db.recordingDao().get(id)?.let {
                    container.db.recordingDao().update(it.copy(status = RecStatus.FAILED, statusReason = "Failed — no USB drive found"))
                }
                repo.prompt.value = Prompt.Notice("Recording failed: no USB drive found")
                return@launch
            }
            container.db.recordingDao().get(id)?.let { r -> repo.running.update { it + (id to r) } }
            withContext(Dispatchers.Main) { holdLocks(s.keepAwake); notify(repo.running.value.values.map { it.title }) }
            val status = runRecording(id, container.db, store, StreamCapture(container.http),
                otherStreams = { repo.running.value.size > 1 || container.isWatching() })
            container.db.recordingDao().get(id)?.let { r ->
                if (status == RecStatus.FAILED || r.statusReason != null && status == RecStatus.STOPPED) repo.prompt.value = Prompt.Notice("${r.title}: ${r.statusReason}")
            }
        }.also { job ->
            job.invokeOnCompletion {
                repo.running.update { it - id }
                scope.launch(Dispatchers.Main) { jobs.remove(id); finishIfIdle(); if (jobs.isNotEmpty()) notify(repo.running.value.values.map { it.title }) }
            }
        }
    }

    private fun finishIfIdle() {
        if (jobs.isNotEmpty()) return
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun holdLocks(keepAwake: Boolean) {
        if (!keepAwake || wake != null) return
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "luna:recording").apply { acquire() }
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifi = runCatching { applicationContext.getSystemService(WifiManager::class.java).createWifiLock(mode, "luna:recording").apply { acquire() } }
            .onFailure { Log.w("IptvRecord", "no Wi-Fi lock: $it") }.getOrNull()
    }

    private fun releaseLocks() {
        wake?.let { if (it.isHeld) it.release() }; wake = null
        wifi?.let { if (it.isHeld) it.release() }; wifi = null
    }

    private fun notify(titles: List<String>) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        val text = when (titles.size) { 0 -> "Starting a recording"; 1 -> "Recording ${titles[0]}"; else -> "Recording ${titles.size} programmes" }
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tab_recordings).setContentTitle(text).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel() // only when Android kills the service: running rows become "app was closed" at the next start
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "tv.luna.player.recording.RUN"
        private const val ACTION_STOP = "tv.luna.player.recording.STOP"
        private const val EXTRA_ID = "id"
        private const val CHANNEL = "recording"

        fun start(context: Context, id: Long) = context.startForegroundService(
            Intent(context, RecordingService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id))

        fun stop(context: Context, id: Long) = context.startService(
            Intent(context, RecordingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id))
    }
}
