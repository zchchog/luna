package tv.luna.player.recording

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.inMemoryDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RecordingSchedulerTest {
    private lateinit var db: AppDatabase
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val started = mutableListOf<Long>()
    private val now = 1_000_000_000L
    private val MIN = 60_000L

    @Before fun setUp() { db = inMemoryDb() }
    @After fun tearDown() { db.close() }

    private fun rec(start: Long, end: Long, status: RecStatus = RecStatus.SCHEDULED, id: Long = 0, channelId: Long = 1) = RecordingEntity(
        id = id, providerId = 1, channelId = channelId, channelName = "C", channelLogo = null, title = "T$start", description = null,
        scheduledStart = start, scheduledEnd = end, programmeStart = start, status = status)

    private fun scheduler() = RecordingScheduler(context, db.recordingDao(), startNow = { started += it }, now = { now })
    private fun alarms() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms

    @Test fun futureIsArmedPastEndIsMissedRunningOneIsLateStart() {
        val plan = planOnRearm(listOf(
            rec(now + MIN, now + 9 * MIN, id = 1), rec(now - MIN, now + MIN, id = 2), rec(now - 9 * MIN, now - MIN, id = 3),
            rec(now - MIN, now + MIN, RecStatus.RECORDING, id = 4), rec(now - MIN, now + MIN, RecStatus.RECORDING, id = 5),
            rec(now - 9 * MIN, now - MIN, RecStatus.DONE, id = 6),
        ), now, running = setOf(5L))
        assertEquals(listOf(1L), plan.arm.map { it.id })
        assertEquals(listOf(2L), plan.startNow.map { it.id })
        assertEquals(listOf(3L), plan.missed.map { it.id })
        assertEquals(listOf(4L), plan.orphaned.map { it.id })
    }

    @Test fun rearmClosesOrphanedRecordings() = runTest {
        val dao = db.recordingDao()
        val orphan = dao.insert(rec(now - MIN, now + MIN, RecStatus.RECORDING).copy(bytes = 5000, location = "/x.ts"))
        val missed = dao.insert(rec(now - 9 * MIN, now - MIN))
        val late = dao.insert(rec(now - MIN, now + MIN))
        val future = dao.insert(rec(now + 5 * MIN, now + 9 * MIN))
        scheduler().rearm(running = { emptySet() })
        dao.get(orphan)!!.let { assertEquals(RecStatus.STOPPED, it.status); assertEquals("Stopped — app was closed", it.statusReason); assertEquals(5000L, it.bytes) }
        dao.get(missed)!!.let { assertEquals(RecStatus.MISSED, it.status); assertEquals("Missed — the box was off", it.statusReason) }
        assertEquals(listOf(late), started)
        assertEquals(listOf(now + 4 * MIN, now + 5 * MIN), alarms().map { it.triggerAtMs }.sorted())
        assertEquals(RecStatus.SCHEDULED, dao.get(future)!!.status)
    }

    @Test fun backToBackTrimsEarlierEnd() {
        val a = rec(1000, 3500, id = 1)
        val (b, changed) = trimBackToBack(rec(2800, 5000), listOf(a))
        assertEquals(5000L, b.scheduledEnd)
        assertEquals(listOf(a.copy(scheduledEnd = 2800)), changed)
    }

    @Test fun backToBackTrimsNewWhenEarlier() {
        val b = rec(2800, 5000, id = 2)
        val (a, changed) = trimBackToBack(rec(1000, 3500), listOf(b))
        assertEquals(2800L, a.scheduledEnd)
        assertEquals(emptyList<RecordingEntity>(), changed)
    }

    @Test fun backToBackIgnoresNonOverlapping() {
        val (n, changed) = trimBackToBack(rec(5000, 6000), listOf(rec(1000, 3500, id = 1)))
        assertEquals(6000L, n.scheduledEnd); assertEquals(emptyList<RecordingEntity>(), changed)
    }

    @Test fun armSetsWarnAndStartAlarms() {
        scheduler().arm(rec(now + 10 * MIN, now + 20 * MIN, id = 7))
        assertEquals(listOf(now + 9 * MIN, now + 10 * MIN), alarms().map { it.triggerAtMs }.sorted())
    }

    @Test fun armSkipsPassedWarning() {
        scheduler().arm(rec(now + 30_000, now + 20 * MIN, id = 7))
        assertEquals(listOf(now + 30_000), alarms().map { it.triggerAtMs })
    }

    @Test fun cancelRemovesBothAlarms() {
        val s = scheduler()
        s.arm(rec(now + 10 * MIN, now + 20 * MIN, id = 7))
        s.arm(rec(now + 30 * MIN, now + 40 * MIN, id = 8))
        s.cancel(7)
        assertEquals(listOf(now + 29 * MIN, now + 30 * MIN), alarms().map { it.triggerAtMs }.sorted())
    }
}
