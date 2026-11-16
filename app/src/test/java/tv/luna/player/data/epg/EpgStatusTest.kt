package tv.luna.player.data.epg

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.EpgChannelEntity
import tv.luna.player.data.db.EpgLinkEntity
import tv.luna.player.data.db.EpgSourceEntity
import tv.luna.player.data.db.ProgrammeEntity
import tv.luna.player.data.db.inMemoryDb
import tv.luna.player.data.db.insertTestProvider
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class EpgStatusTest {
    private val zone = ZoneId.of("Europe/London")
    private val at = LocalDateTime.of(2026, 11, 10, 12, 4).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun okStatusFormats() {
        assertEquals("OK · 12:04 · 229k programmes", epgOkStatus(at, 229_097, zone))
        assertEquals("OK · 12:04 · 512 programmes", epgOkStatus(at, 512, zone))
    }

    @Test
    fun errorStatusTruncates() {
        assertEquals("HTTP 404", epgErrorStatus(RuntimeException("HTTP 404")))
        assertEquals("IllegalStateException", epgErrorStatus(IllegalStateException()))
        assertEquals(120, epgErrorStatus(RuntimeException("x".repeat(500))).length)
    }

    @Test
    fun offsetFormatAndSteps() {
        assertEquals("+1:30", formatOffset(90))
        assertEquals("-0:15", formatOffset(-15))
        assertEquals("0:00", formatOffset(0))
        assertEquals("-12:00", formatOffset(-720))
        assertEquals(97, OFFSET_STEPS.size)
    }

    @Test
    fun clearGuideKeepsManualLinks() = runTest {
        val db = inMemoryDb()
        val p = db.insertTestProvider()
        val s = db.epgDao().insertSource(
            EpgSourceEntity(providerId = p, url = "http://e/x.xml", priority = 0, lastFetched = 5, lastStatus = "OK"))
        db.epgDao().insertChannels(listOf(EpgChannelEntity(s, "a", "A", "a", null)))
        db.programmeDao().insertAll(listOf(
            ProgrammeEntity(sourceId = s, epgChannelKey = ProgrammeEntity.key(s, "a"), start = 0, stop = 1,
                title = "t", desc = null, episodeNum = null, icon = null)))
        db.channelDao().upsert(listOf(
            ChannelEntity(providerId = p, categoryId = null, remoteId = "1", name = "One", logo = null, number = null, tvgId = null, streamUrl = "http://h/1"),
            ChannelEntity(providerId = p, categoryId = null, remoteId = "2", name = "Two", logo = null, number = null, tvgId = null, streamUrl = "http://h/2"),
        ))
        val (c1, c2) = db.channelDao().byProvider(p).sortedBy { it.remoteId }.map { it.id }
        db.epgDao().upsertLinks(listOf(EpgLinkEntity(c1, s, "a", manual = true), EpgLinkEntity(c2, s, "a", manual = false)))

        clearGuide(db)

        assertEquals(0, db.programmeDao().count())
        assertTrue(db.epgDao().channels(s).isEmpty())
        assertEquals(listOf(c1), db.epgDao().links(p).map { it.channelId })
        val src = db.epgDao().source(s)!!
        assertNull(src.lastStatus)
        assertNull(src.lastFetched)
        db.close()
    }
}
