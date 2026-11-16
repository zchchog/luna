package tv.luna.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.luna.player.data.db.CategoryEntity
import tv.luna.player.data.model.Kind

class KeepOrFirstTest {
    private fun cat(id: Long, provider: Long = 1) = CategoryEntity(id = id, providerId = provider, kind = Kind.LIVE, remoteId = "$id", name = "c$id")

    @Test
    fun keepsCurrentWhenStillListedElseFirst() {
        assertEquals(2L, keepOrFirst(cat(2), listOf(cat(1), cat(2)))?.id)
        assertEquals(10L, keepOrFirst(cat(2), listOf(cat(10, provider = 2), cat(11, provider = 2)))?.id) // provider switched
        assertEquals(1L, keepOrFirst(null, listOf(cat(1)))?.id)
        assertNull(keepOrFirst(cat(2), emptyList()))
    }

    @Test
    fun startsOnThePlayingChannelsCategory() {
        assertEquals(3L, keepOrFirst(null, listOf(cat(1), cat(3)), preferId = 3)?.id)
        assertEquals(1L, keepOrFirst(null, listOf(cat(1), cat(3)), preferId = 99)?.id) // its category is hidden/gone
        assertEquals(1L, keepOrFirst(cat(1), listOf(cat(1), cat(3)), preferId = 3)?.id) // user's choice wins later
    }
}
