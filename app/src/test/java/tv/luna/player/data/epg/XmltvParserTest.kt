package tv.luna.player.data.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class XmltvParserTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun parsesChannelsAndProgrammes() {
        val channels = mutableListOf<XChannel>()
        val programmes = mutableListOf<XProgramme>()
        val skipped = XmltvParser().parse(javaClass.getResourceAsStream("/xmltv/guide.xml")!!, channels::add, programmes::add)

        assertEquals(0, skipped)
        assertEquals(
            listOf(XChannel("bbc1.uk", listOf("BBC One", "BBC One HD"), "http://i/bbc1.png"), XChannel("itv1.uk", listOf("ITV 1"), null)),
            channels,
        )
        assertEquals(listOf("News at Six", "Regional News", "Quiz"), programmes.map { it.title })
        val news = programmes[0]
        assertEquals("bbc1.uk", news.channelId)
        assertEquals(ms("2026-11-08T18:00:00Z"), news.start)
        assertEquals(ms("2026-11-08T18:30:00Z"), news.stop)
        assertEquals("The latest & greatest news.", news.desc)
        assertEquals("S1E5", news.episodeNum)
        assertEquals("http://i/news.png", news.icon)
        assertNull(programmes[1].desc)
        assertEquals(ms("2026-11-08T18:00:00Z"), programmes[2].start)
        assertEquals("Questions.", programmes[2].desc)
    }

    @Test
    fun timeOffsets() {
        assertEquals(ms("2026-10-25T00:30:00Z"), XmltvTime.parse("20261025013000 +0100"))
        assertEquals(ms("2026-10-25T01:30:00Z"), XmltvTime.parse("20261025013000 +0000"))
        assertEquals(ms("2026-10-25T01:30:00Z"), XmltvTime.parse("20261025013000"))
        assertEquals(ms("2026-10-25T06:00:00Z"), XmltvTime.parse("20261025013000 -0430"))
        assertNull(XmltvTime.parse("garbage"))
        assertNull(XmltvTime.parse("20261345013000"))
    }

    @Test
    fun toleratesBareAmpersandAndHtmlEntities() {
        val xml = """
            <tv>
              <channel id="a"><display-name>M&S TV</display-name></channel>
              <programme start="20261108180000 +0000" stop="20261108183000 +0000" channel="a"><title>Tom & Jerry</title><desc>Cat&nbsp;and mouse</desc></programme>
            </tv>
        """.trimIndent()
        val channels = mutableListOf<XChannel>()
        val programmes = mutableListOf<XProgramme>()
        val skipped = XmltvParser().parse(xml.byteInputStream(), channels::add, programmes::add)
        assertEquals(0, skipped)
        assertEquals("M&S TV", channels.single().displayNames.single())
        assertEquals("Tom & Jerry", programmes.single().title)
        assertEquals(true, programmes.single().desc!!.startsWith("Cat"))
    }

    @Test(expected = Exception::class)
    fun truncatedBeforeRootEndThrows() {
        val xml = """<tv><programme start="20261108180000 +0000" stop="20261108183000 +0000" channel="a"><title>T</title></programme>"""
        XmltvParser().parse(xml.byteInputStream(), {}, {})
    }

    @Test
    fun skipsBadProgramme() {
        val xml = """
            <tv>
              <programme start="garbage" stop="20261108183000 +0000" channel="a"><title>Bad</title></programme>
              <programme start="20261108180000 +0000" stop="20261108183000 +0000" channel="a"><title>Good</title></programme>
            </tv>
        """.trimIndent()
        val programmes = mutableListOf<XProgramme>()
        val skipped = XmltvParser().parse(xml.byteInputStream(), {}, programmes::add)
        assertEquals(1, skipped)
        assertEquals(listOf("Good"), programmes.map { it.title })
    }
}
