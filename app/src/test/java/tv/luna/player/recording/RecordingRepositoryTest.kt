package tv.luna.player.recording

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.CategoryEntity
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.insertTestProvider
import tv.luna.player.data.model.Kind
import tv.luna.player.data.repo.GuideProgramme
import tv.luna.player.data.settings.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecordingRepositoryTest {
    private lateinit var db: AppDatabase
    private val MIN = 60_000L
    private val now = 1_000_000_000L
    private var limit: Int? = null
    private var watching = false
    private val armed = mutableListOf<Long>(); private val disarmed = mutableListOf<Long>()
    private val started = mutableListOf<Long>(); private val stopped = mutableListOf<Long>()
    private var playerStops = 0
    private var limitHangs = false; private var startFails = false; private var activeProvider: Long? = 1L
    private var settings = AppSettings(recordingOn = true)

    // Room's queries run inline, so work the repository launches finishes within advanceUntilIdle.
    @Before fun setUp() {
        db = androidx.room.Room.inMemoryDatabaseBuilder(androidx.test.core.app.ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
    }
    @After fun tearDown() { db.close() }

    private fun TestScope.repo() = RecordingRepository(
        db, settings = { settings }, arm = { armed += it.id }, disarm = { disarmed += it },
        limitOf = { if (limitHangs) kotlinx.coroutines.awaitCancellation() else limit }, storedLimit = { limit },
        activeProviderId = { activeProvider },
        startService = { if (startFails) throw IllegalStateException("not allowed"); started += it }, stopService = { stopped += it }, stopPlayer = { playerStops++ },
        watching = { watching }, showings = { emptyList() }, scope = this, now = { now },
    )

    private suspend fun channel(): ChannelEntity {
        val p = db.insertTestProvider()
        db.categoryDao().upsert(listOf(CategoryEntity(providerId = p, kind = Kind.LIVE, remoteId = "c", name = "c")))
        val cat = db.categoryDao().byRemoteIds(p, Kind.LIVE).single().id
        db.channelDao().upsert(listOf(ChannelEntity(providerId = p, categoryId = cat, remoteId = "1", name = "BBC One", logo = "http://l/1.png",
            number = 1, tvgId = null, streamUrl = "http://s/1.ts")))
        return db.channelDao().byProvider(p).single()
    }
    private fun prog(start: Long, stop: Long, title: String = "News") = GuideProgramme(title, "d", null, start, stop)
    private suspend fun only() = db.recordingDao().withStatus(*RecStatus.entries.toTypedArray()).single()

    @Test fun recordNowEndsAtProgrammeEndPlusPadding() = runTest {
        val r = repo(); val ch = channel()
        r.recordNow(ch, "BBC One", prog(now - 10 * MIN, now + 20 * MIN))
        val row = only()
        assertEquals(now, row.scheduledStart); assertEquals(now + 25 * MIN, row.scheduledEnd)
        assertEquals("News", row.title); assertEquals("http://l/1.png", row.channelLogo); assertEquals(null, row.programmeStart)
        assertEquals(listOf(row.id), started)
    }

    @Test fun recordNowWithoutGuideIsCappedAtFourHours() = runTest {
        val r = repo(); r.recordNow(channel(), "BBC One", null)
        only().let { assertEquals(now + 240 * MIN, it.scheduledEnd); assertEquals("Live recording", it.title) }
    }

    @Test fun recordNowWhileWatchingWithLimitOneAsks() = runTest {
        limit = 1; watching = true
        val r = repo(); r.recordNow(channel(), "BBC One", null)
        val p = r.prompt.value as Prompt.RecordNowStopsWatching
        assertTrue(started.isEmpty())
        p.onRecord(); advanceUntilIdle()
        assertEquals(1, playerStops); assertEquals(1, started.size)
    }

    @Test fun scheduleAppliesPaddingAndArms() = runTest {
        val r = repo(); val ch = channel()
        r.schedule(ch, "BBC One", prog(now + 60 * MIN, now + 90 * MIN, "Film"))
        only().let {
            assertEquals(now + 58 * MIN, it.scheduledStart); assertEquals(now + 95 * MIN, it.scheduledEnd)
            assertEquals(now + 60 * MIN, it.programmeStart); assertEquals(listOf(it.id), armed)
        }
        assertTrue((r.prompt.value as Prompt.Notice).text.startsWith("Scheduled Film"))
    }

    @Test fun scheduleTrimsBackToBack() = runTest {
        val r = repo(); val ch = channel()
        r.schedule(ch, "BBC One", prog(now + 60 * MIN, now + 90 * MIN, "A"))
        r.schedule(ch, "BBC One", prog(now + 90 * MIN, now + 120 * MIN, "B"))
        val rows = db.recordingDao().withStatus(RecStatus.SCHEDULED).associateBy { it.title }
        assertEquals(rows.getValue("B").scheduledStart, rows.getValue("A").scheduledEnd)
    }

    @Test fun warnWhileWatchingWithLimitOnePromptsAndKeepWatchingSkips() = runTest {
        limit = 1; watching = true
        val r = repo(); val ch = channel()
        r.schedule(ch, "BBC One", prog(now + 2 * MIN, now + 30 * MIN))
        val id = only().id
        r.onWarn(id)
        val p = r.prompt.value as Prompt.StartsSoon
        assertEquals("News", p.title); assertEquals("BBC One", p.channel)
        r.keepWatching(id); advanceUntilIdle()
        r.onStart(id)
        only().let { assertEquals(RecStatus.SKIPPED, it.status); assertEquals("Skipped — you were watching", it.statusReason) }
        assertTrue(started.isEmpty()); assertEquals(0, playerStops)
    }

    @Test fun warnWithFreeConnectionDoesNotPrompt() = runTest {
        limit = 2; watching = true
        val r = repo(); r.schedule(channel(), "BBC One", prog(now + 2 * MIN, now + 30 * MIN))
        r.prompt.value = null
        r.onWarn(only().id)
        assertEquals(null, r.prompt.value)
    }

    @Test fun startWithNoAnswerStopsPlayerAndRecords() = runTest {
        limit = 1; watching = true
        val r = repo(); r.schedule(channel(), "BBC One", prog(now + 2 * MIN, now + 30 * MIN))
        val id = only().id
        r.onStart(id)
        assertEquals(1, playerStops); assertEquals(listOf(id), started)
        assertTrue((r.prompt.value as Prompt.Notice).leavePlayer)
    }

    @Test fun secondRecordingWithLimitOneFailsNoFreeConnection() = runTest {
        limit = 1
        val r = repo(); val ch = channel()
        r.schedule(ch, "BBC One", prog(now + 2 * MIN, now + 30 * MIN))
        val id = only().id
        r.running.value = mapOf(99L to only().copy(id = 99, channelId = 999))
        r.onStart(id)
        db.recordingDao().get(id)!!.let { assertEquals(RecStatus.FAILED, it.status); assertEquals("No free connection", it.statusReason) }
    }

    @Test fun keepRecordingSilencesFurtherAsks() = runTest {
        limit = 1
        val r = repo()
        r.running.value = mapOf(5L to RecordingEntity(id = 5, providerId = 1, channelId = 1, channelName = "C", channelLogo = null,
            title = "Match", description = null, scheduledStart = 0, scheduledEnd = 1, programmeStart = null))
        var retried = 0
        assertTrue(r.blocksWatching { retried++ })
        val ask = r.prompt.value as Prompt.StopRecordingToWatch
        assertEquals("Match", ask.title)
        r.keepRecording()
        assertTrue(r.blocksWatching { retried++ })
        assertEquals("Recording Match is using your only connection", (r.prompt.value as Prompt.Notice).text)
        ask.onStop()
        assertEquals(listOf(5L), stopped); assertEquals(0, retried) // the recording still holds the connection
        r.running.value = emptyMap()
        advanceUntilIdle(); assertEquals(1, retried)
        assertEquals(false, r.blocksWatching { })
    }

    @Test fun watchingWithFreeConnectionIsNotBlocked() = runTest {
        limit = 2
        val r = repo(); r.running.value = mapOf(5L to RecordingEntity(id = 5, providerId = 1, channelId = 1, channelName = "C",
            channelLogo = null, title = "M", description = null, scheduledStart = 0, scheduledEnd = 1, programmeStart = null))
        assertEquals(false, r.blocksWatching { })
    }

    @Test fun turnOffCancelsScheduledAndStopsRunning() = runTest {
        val r = repo(); val ch = channel()
        r.schedule(ch, "BBC One", prog(now + 60 * MIN, now + 90 * MIN))
        val id = only().id
        r.running.value = mapOf(7L to only().copy(id = 7))
        r.turnOff()
        assertEquals(null, db.recordingDao().get(id)); assertEquals(listOf(id), disarmed); assertEquals(listOf(7L), stopped)
    }

    @Test fun startOnDeletedChannelFails() = runTest {
        val r = repo(); val ch = channel()
        r.schedule(ch, "BBC One", prog(now + 60 * MIN, now + 90 * MIN))
        db.channelDao().deleteIds(listOf(ch.id))
        r.onStart(only().id)
        only().let { assertEquals(RecStatus.FAILED, it.status); assertEquals("Channel no longer in the playlist", it.statusReason) }
    }

    @Test fun startStopsSameChannelRecordingBackToBack() = runTest {
        val r = repo(); val ch = channel()
        r.schedule(ch, "BBC One", prog(now + 60 * MIN, now + 90 * MIN))
        val b = only()
        r.running.value = mapOf(3L to b.copy(id = 3, status = RecStatus.RECORDING))
        limit = 1
        r.onStart(b.id)
        assertEquals(listOf(3L), stopped); assertEquals(listOf(b.id), started)
    }

    @Test fun cancelDeletesScheduledRowAndAlarms() = runTest {
        val r = repo(); r.schedule(channel(), "BBC One", prog(now + 60 * MIN, now + 90 * MIN))
        val id = only().id
        r.cancel(id)
        assertEquals(null, db.recordingDao().get(id)); assertEquals(listOf(id), disarmed)
    }

    @Test fun startOfAnAlreadyRunningRecordingDoesNothing() = runTest {
        val r = repo(); r.schedule(channel(), "BBC One", prog(now + 60 * MIN, now + 90 * MIN))
        val b = only()
        r.running.value = mapOf(b.id to b.copy(status = RecStatus.RECORDING))
        r.onStart(b.id)
        assertEquals(emptyList<Long>(), stopped); assertEquals(emptyList<Long>(), started)
    }

    @Test fun stopToWatchRetriesAfterTimeoutIfRecordingHangs() = runTest {
        limit = 1
        val r = repo()
        r.running.value = mapOf(5L to RecordingEntity(id = 5, providerId = 1, channelId = 1, channelName = "C", channelLogo = null,
            title = "Match", description = null, scheduledStart = 0, scheduledEnd = 1, programmeStart = null))
        var retried = 0
        r.blocksWatching { retried++ }
        (r.prompt.value as Prompt.StopRecordingToWatch).onStop()
        advanceTimeBy(4_000); assertEquals(0, retried)
        advanceUntilIdle(); assertEquals(1, retried)
    }

    @Test fun recordingOnAnotherPlaylistDoesNotBlockWatching() = runTest {
        limit = 1; activeProvider = 2L
        val r = repo(); r.running.value = mapOf(5L to RecordingEntity(id = 5, providerId = 1, channelId = 1, channelName = "C",
            channelLogo = null, title = "M", description = null, scheduledStart = 0, scheduledEnd = 1, programmeStart = null))
        assertEquals(false, r.blocksWatching { })
    }

    @Test fun recordNowIgnoresRecordingsAndPlayerOnAnotherPlaylist() = runTest {
        limit = 1; watching = true; activeProvider = 999L
        val r = repo(); val ch = channel()
        r.running.value = mapOf(5L to RecordingEntity(id = 5, providerId = 999, channelId = 1, channelName = "C",
            channelLogo = null, title = "M", description = null, scheduledStart = 0, scheduledEnd = 1, programmeStart = null))
        r.recordNow(ch, "BBC One", null)
        assertEquals(1, started.size); assertEquals(0, playerStops)
    }

    @Test fun serviceThatCannotStartMarksTheRowFailed() = runTest {
        startFails = true
        val r = repo(); r.schedule(channel(), "BBC One", prog(now + 60 * MIN, now + 90 * MIN))
        r.onStart(only().id)
        assertTrue(r.running.value.isEmpty()); assertEquals(RecStatus.FAILED, only().status)
    }

    @Test fun startFallsBackToStoredLimitWhenProviderIsSlow() = runTest {
        limitHangs = true; limit = 1; watching = true
        val r = repo(); r.schedule(channel(), "BBC One", prog(now + 60 * MIN, now + 90 * MIN))
        r.onStart(only().id)
        assertEquals(1, playerStops); assertEquals(1, started.size)
    }
}
