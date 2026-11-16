package tv.luna.player.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** What to do with the recording rows when alarms must be set again (app start, boot, update). */
data class Rearm(val arm: List<RecordingEntity>, val startNow: List<RecordingEntity>, val missed: List<RecordingEntity>,
                 val orphaned: List<RecordingEntity>)

/** [running]: ids a live job is recording; a RECORDING row without one was cut off by the app closing. */
fun planOnRearm(rows: List<RecordingEntity>, now: Long, running: Set<Long>): Rearm {
    val scheduled = rows.filter { it.status == RecStatus.SCHEDULED }
    return Rearm(
        arm = scheduled.filter { it.scheduledStart > now },
        // The start passed while the box was off or asleep, but the programme is still on: record the rest.
        startNow = scheduled.filter { it.scheduledStart <= now && it.scheduledEnd > now },
        missed = scheduled.filter { it.scheduledEnd <= now },
        orphaned = rows.filter { it.status == RecStatus.RECORDING && it.id !in running },
    )
}

/**
 * Back-to-back on one channel: when padding makes two recordings overlap, the earlier one ends where the later one
 * starts, so one connection is enough. Returns [new] (maybe trimmed) and the existing rows that changed.
 */
fun trimBackToBack(new: RecordingEntity, sameChannel: List<RecordingEntity>): Pair<RecordingEntity, List<RecordingEntity>> {
    var n = new
    val changed = mutableListOf<RecordingEntity>()
    for (o in sameChannel) {
        if (o.scheduledStart <= n.scheduledStart && o.scheduledEnd > n.scheduledStart) changed += o.copy(scheduledEnd = n.scheduledStart)
        else if (o.scheduledStart > n.scheduledStart && n.scheduledEnd > o.scheduledStart) n = n.copy(scheduledEnd = o.scheduledStart)
    }
    return n to changed
}

/** Exact alarms for scheduled recordings: a warning a minute before (connection-limit question) and the start. */
class RecordingScheduler(
    private val context: Context, private val dao: RecordingDao, private val startNow: suspend (Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun arm(r: RecordingEntity) {
        if (r.scheduledStart - WARN_BEFORE > now()) set(r.scheduledStart - WARN_BEFORE, intent(r.id, warn = true))
        set(r.scheduledStart, intent(r.id, warn = false))
    }

    fun cancel(id: Long) {
        alarms.cancel(intent(id, warn = true))
        alarms.cancel(intent(id, warn = false))
    }

    /** [running] is read after the rows, so a recording that starts meanwhile isn't taken for a cut-off one. */
    suspend fun rearm(running: () -> Set<Long>) {
        val rows = dao.withStatus(RecStatus.SCHEDULED, RecStatus.RECORDING)
        val plan = planOnRearm(rows, now(), running())
        plan.orphaned.forEach { dao.update(it.copy(status = RecStatus.STOPPED, statusReason = "Stopped — app was closed", actualEnd = it.actualEnd ?: now())) }
        plan.missed.forEach { dao.update(it.copy(status = RecStatus.MISSED, statusReason = "Missed — the box was off")) }
        plan.arm.forEach(::arm)
        plan.startNow.forEach { startNow(it.id) }
    }

    private fun set(at: Long, pi: PendingIntent) {
        // Android 12–12L asks the user for exact alarms; without them, the start may be a few minutes late.
        if (Build.VERSION.SDK_INT in 31..32 && !alarms.canScheduleExactAlarms()) {
            Log.w("IptvRecord", "exact alarms not allowed; using an inexact one")
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun intent(id: Long, warn: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context, (id * 2 + if (warn) 0 else 1).toInt(),
        Intent(if (warn) RecordingReceiver.ACTION_WARN else RecordingReceiver.ACTION_START).setClass(context, RecordingReceiver::class.java)
            .putExtra(RecordingReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object { const val WARN_BEFORE = 60_000L }
}
