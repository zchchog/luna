package tv.luna.player.data.epg

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.EpgLinkEntity

class SiblingsTest {
    private fun ch(id: Long, name: String) = ChannelEntity(id = id, providerId = 1, categoryId = null, remoteId = "$id", name = name,
        logo = null, number = null, tvgId = null, streamUrl = "u$id")

    private fun link(channelId: Long, xmltvId: String, manual: Boolean = false) = EpgLinkEntity(channelId, 1, xmltvId, manual)

    @Test fun encodingVariantBorrowsFromPlain() {
        val chs = listOf(ch(1, "Sport"), ch(2, "Sport hevc"), ch(3, "Sport RAW"))
        assertEquals(setOf(2L, 3L), borrowSiblings(chs, { "GB" }, listOf(link(1, "s.uk"))).map { it.channelId }.toSet())
    }

    @Test fun neverOverwritesExistingLinks() = assertEquals(emptyList<EpgLinkEntity>(),
        borrowSiblings(listOf(ch(1, "Sport"), ch(2, "Sport HD")), { "GB" }, listOf(link(1, "a"), link(2, "b", manual = true))))

    @Test fun prefersManualDonorThenLowestId() {
        val chs = listOf(ch(5, "News"), ch(7, "News HD"), ch(9, "News RAW"))
        assertEquals("m", borrowSiblings(chs, { "GB" }, listOf(link(5, "a"), link(7, "m", manual = true))).single().xmltvId)
        assertEquals("a", borrowSiblings(chs, { "GB" }, listOf(link(5, "a"), link(7, "b"))).single().xmltvId)
    }

    @Test fun differentCountryDoesNotBorrow() = assertEquals(emptyList<EpgLinkEntity>(),
        borrowSiblings(listOf(ch(1, "UK: Sport"), ch(2, "DE: Sport")), { if (it.id == 1L) "GB" else "DE" }, listOf(link(1, "s.uk"))))

    /** Review: a mixed category ("SPORTS") has no country, so the name's own prefix decides. */
    @Test fun noCategoryCountryUsesNamePrefix() {
        assertEquals(emptyList<EpgLinkEntity>(), borrowSiblings(listOf(ch(1, "UK: Sport"), ch(2, "DE: Sport")), { null }, listOf(link(1, "s.uk"))))
        assertEquals(listOf(2L), borrowSiblings(listOf(ch(1, "UK: Sport"), ch(2, "UK: Sport HD")), { null }, listOf(link(1, "s.uk"))).map { it.channelId })
    }

    @Test fun noChainBorrowing() {
        // "Sport RAW" has no link and no donor of its own key among linked channels except via a borrowed link — it must stay unlinked.
        val chs = listOf(ch(1, "Sport"), ch(2, "Sport hevc"), ch(3, "Other RAW"), ch(4, "Other"))
        val out = borrowSiblings(chs, { "GB" }, listOf(link(1, "s.uk")))
        assertEquals(listOf(2L), out.map { it.channelId })
    }

    @Test fun borrowedLinksAreAutomatic() =
        assertEquals(false, borrowSiblings(listOf(ch(1, "A"), ch(2, "A HD")), { null }, listOf(link(1, "a", manual = true))).single().manual)

    @Test fun donorsSiblingsFirstThenSearch() {
        val target = ch(1, "Sport RAW")
        val withGuide = listOf(ch(5, "News"), ch(4, "Sky Sports"), ch(3, "Sport HD"), ch(2, "Sport"), target)
        assertEquals(listOf("Sport", "Sport HD"), guideDonors(target, withGuide, "").map { it.name })
        assertEquals(listOf("Sport", "Sport HD", "Sky Sports"), guideDonors(target, withGuide, "sky sp").map { it.name })
        assertEquals(listOf("Sport", "Sport HD"), guideDonors(target, withGuide, "sport raw").map { it.name })
    }
}
