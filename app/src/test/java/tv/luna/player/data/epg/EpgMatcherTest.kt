package tv.luna.player.data.epg

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.EpgChannelEntity
import tv.luna.player.data.db.EpgLinkEntity
import tv.luna.player.data.db.EpgSourceEntity
import tv.luna.player.data.db.NameRuleEntity
import tv.luna.player.data.db.NameRuleType
import tv.luna.player.data.db.inMemoryDb
import tv.luna.player.data.db.insertTestProvider
import tv.luna.player.data.settings.GuideKind

@RunWith(RobolectricTestRunner::class)
class EpgMatcherTest {
    private lateinit var db: AppDatabase
    private var p = 0L

    @Before fun setUp() = runTest {
        db = inMemoryDb()
        p = db.insertTestProvider()
        db.ruleDao().insertNameRules(BuiltinRule.entries.mapIndexed { i, r ->
            NameRuleEntity(providerId = p, order = i, enabled = true, type = NameRuleType.BUILTIN, pattern = r.name)
        })
    }

    @After fun tearDown() { db.close() }

    private suspend fun source(priority: Int, offset: Int = 0) =
        db.epgDao().insertSource(EpgSourceEntity(providerId = p, url = "http://e/$priority.xml", priority = priority, offsetMinutes = offset))

    private suspend fun xmltv(sourceId: Long, id: String, vararg names: String) =
        db.epgDao().insertChannels(names.map { EpgChannelEntity(sourceId, id, it, it.lowercase(), null) })

    private suspend fun channel(remoteId: String, name: String, tvgId: String? = null): Long {
        db.channelDao().upsert(listOf(ChannelEntity(providerId = p, categoryId = null, remoteId = remoteId, name = name,
            logo = null, number = null, tvgId = tvgId, streamUrl = "http://h/$remoteId")))
        return db.channelDao().byProvider(p).single { it.remoteId == remoteId }.id
    }

    private suspend fun link(channelId: Long) = db.epgDao().link(channelId)

    @Test
    fun manualWins() = runTest {
        val a = source(0)
        val b = source(1)
        xmltv(a, "bbc1.uk", "BBC One")
        xmltv(b, "other", "Other")
        val c = channel("1", "BBC One", tvgId = "bbc1.uk")
        db.epgDao().upsertLinks(listOf(EpgLinkEntity(c, b, "other", manual = true)))

        EpgMatcher(db).rematch(p)

        assertEquals(EpgLinkEntity(c, b, "other", manual = true), link(c))
    }

    @Test
    fun tvgIdBeforeName() = runTest {
        val a = source(0)
        xmltv(a, "bbc1.uk", "Something Else")
        xmltv(a, "bbc1.alt", "BBC One")
        val c = channel("1", "BBC One", tvgId = "bbc1.uk")

        EpgMatcher(db).rematch(p)

        assertEquals(EpgLinkEntity(c, a, "bbc1.uk", manual = false), link(c))
    }

    @Test
    fun sourcePriority() = runTest {
        val low = source(1)
        val high = source(0)
        xmltv(low, "x", "X")
        xmltv(high, "x", "X")
        val c = channel("1", "Whatever", tvgId = "x")

        EpgMatcher(db).rematch(p)

        assertEquals(high, link(c)!!.sourceId)
    }

    @Test
    fun nameFallback() = runTest {
        val a = source(0)
        xmltv(a, "bbc2.uk", "BBC2", "BBC Two")
        val c = channel("1", "UK: BBC Two HD")

        val r = EpgMatcher(db).rematch(p)

        assertEquals(EpgLinkEntity(c, a, "bbc2.uk", manual = false), link(c))
        assertEquals(MatchResult(matched = 1, unmatched = 0), r)
    }

    @Test
    fun namePriorityAcrossSources() = runTest {
        val low = source(1)
        val high = source(0)
        xmltv(low, "low.id", "Film4")
        xmltv(high, "high.id", "Film4 HD")
        val c = channel("1", "UK - Film4")

        EpgMatcher(db).rematch(p)

        assertEquals("high.id", link(c)!!.xmltvId)
    }

    @Test
    fun plusOneGuideNotUsedForMainChannel() = runTest {
        val a = source(0)
        // +1 rows first, so insertion order would favour them.
        xmltv(a, "Channel4+1.uk", "Channel 4 +1")
        xmltv(a, "Channel4.uk", "Channel 4")
        xmltv(a, "E4+1.uk", "E4 +1")
        xmltv(a, "E4.uk", "E4")
        val main = channel("1", "UK: Channel 4 HD")
        val plus = channel("2", "UK: Channel 4 +1 HD")
        val e4 = channel("3", "UK: E4")
        val e4plus = channel("4", "E4 +1")

        EpgMatcher(db).rematch(p)

        assertEquals("Channel4.uk", link(main)!!.xmltvId)
        assertEquals("Channel4+1.uk", link(plus)!!.xmltvId)
        assertEquals("E4.uk", link(e4)!!.xmltvId)
        assertEquals("E4+1.uk", link(e4plus)!!.xmltvId)
    }

    @Test
    fun plusOneChannelFallsBackToMainGuide() = runTest {
        val a = source(0)
        xmltv(a, "Dave.uk", "Dave")
        val c = channel("1", "Dave +1")
        EpgMatcher(db).rematch(p)
        assertEquals("Dave.uk", link(c)!!.xmltvId)
    }

    @Test
    fun autoLinksRewrittenAndUnmatchedCounted() = runTest {
        val a = source(0)
        xmltv(a, "gone.id", "Gone")
        val c = channel("1", "Mystery", tvgId = "gone.id")
        val d = channel("2", "Nothing")
        EpgMatcher(db).rematch(p)
        assertEquals("gone.id", link(c)!!.xmltvId)

        db.epgDao().deleteChannels(a)
        val r = EpgMatcher(db).rematch(p)

        assertNull(link(c))
        assertNull(link(d))
        assertEquals(MatchResult(matched = 0, unmatched = 2), r)
    }

    @Test
    fun offsetResolution() = runTest {
        val src = EpgSourceEntity(id = 1, providerId = p, url = "u", priority = 0, offsetMinutes = -30)
        val ch = ChannelEntity(providerId = p, categoryId = null, remoteId = "1", name = "A", logo = null, number = null, tvgId = null, streamUrl = "u")
        assertEquals(60, effectiveOffsetMinutes(ch.copy(epgOffsetMinutes = 60), src))
        assertEquals(-30, effectiveOffsetMinutes(ch, src))
    }

    @Test fun modeDecidesWhichSourcesMatch() = runTest {
        val pl = source(0)
        val share = db.epgDao().insertSource(EpgSourceEntity(providerId = p, url = "http://share/uk.xml.gz", priority = 0,
            origin = tv.luna.player.data.db.ORIGIN_EPGSHARE))
        xmltv(pl, "a.uk", "A")
        xmltv(share, "a.uk", "A"); xmltv(share, "b.uk", "B")
        val a = channel("1", "A", tvgId = "a.uk"); val b = channel("2", "B", tvgId = "b.uk")
        val m = EpgMatcher(db)

        m.rematch(p, listOf(GuideKind.PLAYLIST))
        assertEquals(pl, link(a)!!.sourceId); assertNull(link(b))
        m.rematch(p, listOf(GuideKind.PLAYLIST, GuideKind.OPEN_SOURCE))
        assertEquals(pl, link(a)!!.sourceId); assertEquals(share, link(b)!!.sourceId)
        m.rematch(p, listOf(GuideKind.OPEN_SOURCE))
        assertEquals(share, link(a)!!.sourceId); assertEquals(share, link(b)!!.sourceId)
        m.rematch(p, listOf(GuideKind.OPEN_SOURCE, GuideKind.PLAYLIST))   // reverse: open-source first, playlist fills
        assertEquals(share, link(a)!!.sourceId); assertEquals(share, link(b)!!.sourceId)
    }

    @Test fun borrowedRecomputedOnRematch() = runTest {
        val s = source(0)
        xmltv(s, "sport.uk", "Sport Channel")  // donor links by tvg-id; the guide's name differs
        val plain = channel("1", "Sport", tvgId = "sport.uk"); val hevc = channel("2", "Sport hevc")
        val r = EpgMatcher(db).rematch(p)
        assertEquals("sport.uk", link(plain)!!.xmltvId); assertEquals("sport.uk", link(hevc)!!.xmltvId)
        assertEquals(1, r.borrowed); assertEquals(2, r.matched)

        db.epgDao().deleteChannels(s)
        EpgMatcher(db).rematch(p)
        assertNull(link(plain)); assertNull(link(hevc))
    }

    @Test fun borrowOffLeavesSiblingsUnlinked() = runTest {
        val s = source(0)
        xmltv(s, "sport.uk", "Sport Channel")  // donor links by tvg-id; the guide's name differs
        channel("1", "Sport", tvgId = "sport.uk"); val hevc = channel("2", "Sport hevc")
        EpgMatcher(db).rematch(p, borrow = false)
        assertNull(link(hevc))
    }

    @Test fun manualUseGuideFromSurvivesRematchAndBorrow() = runTest {
        val s = source(0)
        xmltv(s, "sport.uk", "Sport"); xmltv(s, "news.uk", "News")
        channel("1", "Sport"); val hevc = channel("2", "Sport hevc"); val news = channel("3", "News")
        EpgMatcher(db).rematch(p)
        val donor = link(news)!!
        db.epgDao().upsertLinks(listOf(EpgLinkEntity(hevc, donor.sourceId, donor.xmltvId, manual = true)))
        repeat(2) { EpgMatcher(db).rematch(p, borrow = true) }
        assertEquals("news.uk", link(hevc)!!.xmltvId); assertEquals(true, link(hevc)!!.manual)
    }

    @Test fun useAutomaticGuideRematches() = runTest {
        val s = source(0)
        xmltv(s, "sport.uk", "Sport"); xmltv(s, "news.uk", "News")
        val sport = channel("1", "Sport")
        db.epgDao().upsertLinks(listOf(EpgLinkEntity(sport, s, "news.uk", manual = true)))
        EpgMatcher(db).rematch(p)
        assertEquals("news.uk", link(sport)!!.xmltvId)
        db.epgDao().deleteLink(sport)
        EpgMatcher(db).rematch(p)
        assertEquals("sport.uk", link(sport)!!.xmltvId); assertEquals(false, link(sport)!!.manual)
    }

    /** Open-source files say "BBC Two HD" / "ITV1 HD"; providers say "UK: BBC 2 HEVC HD" / "UK: ITV 1 4K ◉". */
    @Test fun compactNamesMatch() = runTest {
        val s = source(0)
        xmltv(s, "BBC.Two.HD.uk", "BBC Two HD"); xmltv(s, "ITV1.HD.uk", "ITV1 HD"); xmltv(s, "Sky.Sports.10.uk", "Sky Sports 10")
        val bbc2 = channel("1", "UK: BBC 2 HEVC HD"); val itv = channel("2", "UK: ITV 1 4K ◉"); val ss1 = channel("3", "UK: Sky Sports 1 HD")
        EpgMatcher(db).rematch(p, borrow = false)
        assertEquals("BBC.Two.HD.uk", link(bbc2)!!.xmltvId); assertEquals("ITV1.HD.uk", link(itv)!!.xmltvId); assertNull(link(ss1))
    }

    private suspend fun programme(sourceId: Long, xmltvId: String, start: Long, stop: Long) =
        db.programmeDao().insertAll(listOf(tv.luna.player.data.db.ProgrammeEntity(sourceId = sourceId,
            epgChannelKey = tv.luna.player.data.db.ProgrammeEntity.key(sourceId, xmltvId), start = start, stop = stop,
            title = "t", desc = null, episodeNum = null, icon = null)))

    /** User 2026-11-11: "+ open-source" also fills a channel whose playlist guide has run out. */
    @Test fun runOutPlaylistGuideFilledFromOpenSource() = runTest {
        val now = 1_000_000_000L; val hour = 3_600_000L
        val pl = source(0)
        val share = db.epgDao().insertSource(EpgSourceEntity(providerId = p, url = "http://share/uk.xml.gz", priority = 0,
            origin = tv.luna.player.data.db.ORIGIN_EPGSHARE))
        xmltv(pl, "cc.uk", "Comedy Central"); xmltv(share, "ComedyCentral.uk", "Comedy Central")
        xmltv(pl, "e4.uk", "E4"); xmltv(share, "E4.uk", "E4")
        programme(pl, "cc.uk", now - 5 * hour, now - 4 * hour)            // ran out
        programme(share, "ComedyCentral.uk", now - hour, now + hour)
        programme(pl, "e4.uk", now - hour, now + hour)                    // still running: playlist stays first
        programme(share, "E4.uk", now - hour, now + hour)
        val cc = channel("1", "Comedy Central", tvgId = "cc.uk"); val e4 = channel("2", "E4", tvgId = "e4.uk")
        val m = EpgMatcher(db, now = { now })
        m.rematch(p, listOf(GuideKind.PLAYLIST, GuideKind.OPEN_SOURCE))
        assertEquals(share, link(cc)!!.sourceId); assertEquals(pl, link(e4)!!.sourceId)
        m.rematch(p, listOf(GuideKind.PLAYLIST))   // nothing to fill from: keep the playlist link
        assertEquals(pl, link(cc)!!.sourceId)
    }

    @Test
    fun countryWordOnlyDroppedOnTheChannelSide() = runTest {
        val a = source(0)
        xmltv(a, "cc.us", "Comedy Central US") // only the US feed in this guide
        xmltv(a, "dunya.uk", "Dunya News")
        val uk = channel("1", "UK: Comedy Central HD")
        val dunya = channel("2", "Dunya News UK")

        EpgMatcher(db).rematch(p)

        assertEquals(null, link(uk)) // unmatched beats another country's schedule
        assertEquals("dunya.uk", link(dunya)?.xmltvId) // the channel's own country word still falls away
    }
}
