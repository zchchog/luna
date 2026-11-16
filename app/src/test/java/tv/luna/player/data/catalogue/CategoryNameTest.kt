package tv.luna.player.data.catalogue

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.luna.player.data.db.AppDatabase
import tv.luna.player.data.db.CategoryEntity
import tv.luna.player.data.db.ChannelEntity
import tv.luna.player.data.db.inMemoryDb
import tv.luna.player.data.db.insertTestProvider
import tv.luna.player.data.model.Kind
import tv.luna.player.data.repo.RailRepository

@RunWith(RobolectricTestRunner::class)
class CategoryNameTest {
    private lateinit var db: AppDatabase
    @Before fun setUp() { db = inMemoryDb() }
    @After fun tearDown() { db.close() }

    private fun cat(name: String) = CategoryEntity(providerId = 1, kind = Kind.LIVE, remoteId = "1", name = name)

    @Test fun categoryNameStripsAndRenames() =
        assertEquals("GENERAL HEVC", categoryName(cat("UK| GENERAL ʰᵉᵛᶜ"), listOf("UK|", "hevc=HEVC")))

    @Test fun categoryNameNeverEmpty() {
        assertEquals("UK|", categoryName(cat("UK|"), listOf("UK|")))
        assertEquals("Uncategorised", categoryName(cat(""), emptyList()))
    }

    @Test fun railUsesCategoryStrip() = runTest {
        val p = db.insertTestProvider()
        db.categoryDao().upsert(listOf(CategoryEntity(providerId = p, kind = Kind.LIVE, remoteId = "1", name = "UK| News")))
        val c = db.categoryDao().byRemoteIds(p, Kind.LIVE).single().id
        db.channelDao().upsert(listOf(ChannelEntity(providerId = p, categoryId = c, remoteId = "a", name = "a", logo = null,
            number = null, tvgId = null, streamUrl = "http://s/a")))
        val repo = RailRepository(db)
        assertEquals("UK| News", repo.rail(p).first().last().entry.title)
        db.providerDao().setCategoryStrip(p, "UK|")
        assertEquals("News", repo.rail(p).first().last().entry.title)
    }
}
