package tv.luna.player.reminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tv.luna.player.IptvApp
import tv.luna.player.MainActivity
import tv.luna.player.R
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.ReminderEntity
import tv.luna.player.data.repo.GuideProgramme
import tv.luna.player.recording.Prompt

/*
 * TV guide reminders: everything stays on the box. A reminder is a row in Luna's own database and an alarm; when it goes
 * off a minute before the programme, Luna shows a pop-up (if it's open) or a notification (if it isn't).
 */

@Dao
interface ReminderDao {
    @Insert suspend fun insert(r: ReminderEntity): Long
    @Query("DELETE FROM reminder WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM reminder WHERE id = :id") suspend fun get(id: Long): ReminderEntity?
    @Query("SELECT * FROM reminder") suspend fun all(): List<ReminderEntity>
    @Query("SELECT * FROM reminder") fun observe(): Flow<List<ReminderEntity>>
}

/** Whether a Luna screen is showing (set by MainActivity), so a reminder knows to pop up rather than notify. */
object AppVisible { @Volatile var value = false }

class Reminders(private val context: Context, private val db: AppDatabase, private val prompt: kotlinx.coroutines.flow.MutableStateFlow<Prompt?>) {
    private val dao get() = db.reminderDao()
    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** (channelId, programme start) with a reminder: the menu's mark. */
    val marks: Flow<Set<Pair<Long, Long>>> = dao.observe().map { list -> list.map { it.channelId to it.programmeStart }.toSet() }

    suspend fun add(ch: ChannelEntity, p: GuideProgramme) {
        if (dao.all().any { it.channelId == ch.id && it.programmeStart == p.start }) return
        val id = dao.insert(ReminderEntity(channelId = ch.id, programmeStart = p.start, title = p.title))
        arm(id, p.start)
    }

    suspend fun cancel(ch: ChannelEntity, p: GuideProgramme) {
        dao.all().filter { it.channelId == ch.id && it.programmeStart == p.start }.forEach { alarms.cancel(intent(it.id)); dao.delete(it.id) }
    }

    /** After a restart (boot and updates clear alarms): set them again, and drop ones long past. */
    suspend fun rearm() {
        val now = System.currentTimeMillis()
        for (r in dao.all()) if (r.programmeStart > now - 5 * 60_000) arm(r.id, r.programmeStart) else dao.delete(r.id)
    }

    private fun arm(id: Long, start: Long) {
        val at = (start - BEFORE).coerceAtLeast(System.currentTimeMillis() + 1_000)
        if (Build.VERSION.SDK_INT in 31..32 && !alarms.canScheduleExactAlarms()) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(id))
        else alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(id))
    }

    private fun intent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, (1_000_000 + id).toInt(),
        Intent(ACTION).setClass(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    suspend fun fire(id: Long) {
        val r = dao.get(id) ?: return
        dao.delete(id)
        val ch = db.channelDao().get(r.channelId) ?: return
        val name = ch.customName ?: ch.name
        if (AppVisible.value) prompt.value = Prompt.Reminder(r.title, name, ch.id)
        else notify(r, name, ch.id)
    }

    private fun notify(r: ReminderEntity, channel: String, channelId: Long) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "TV guide reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, r.id.toInt(),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_WATCH, channelId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tab_live)
            .setContentTitle("Starting now: ${r.title}")
            .setContentText("On $channel. Open Luna to watch.")
            .setContentIntent(open).setAutoCancel(true)
            .build()
        runCatching { nm.notify(r.id.toInt(), n) }.onFailure { Log.w(TAG, "reminder notification not allowed: $it") }
    }

    companion object {
        const val ACTION = "tv.luna.player.reminders.FIRE"
        const val EXTRA_ID = "id"
        /** On MainActivity's intent: the channel to start (from a reminder). */
        const val EXTRA_WATCH = "watch_channel"
        private const val CHANNEL = "reminders"
        private const val BEFORE = 60_000L
        private const val TAG = "LunaReminders"
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as IptvApp).container
        val id = intent.getLongExtra(Reminders.EXTRA_ID, -1L)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { c.reminders.fire(id) } catch (e: Exception) { Log.w("LunaReminders", "reminder failed: $e") } finally { pending.finish() }
        }
    }
}
