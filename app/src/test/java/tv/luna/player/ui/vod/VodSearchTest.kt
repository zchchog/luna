package tv.luna.player.ui.vod

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.luna.player.data.model.Kind

class VodSearchTest {
    private fun item(id: Long, cat: Long) = VodItem(id, Kind.MOVIE, "r$id", "Minecraft $id", null, null, null, cat, null)

    /** A fake in-category search over two categories: A (1) holds item 1, B (2) holds item 2. */
    private val calls = mutableListOf<Triple<Kind, Long?, String>>()
    private val search: suspend (Kind, Long?, String) -> List<VodItem> = { k, cat, q ->
        calls += Triple(k, cat, q)
        listOf(item(1, 1), item(2, 2)).filter { cat == null || it.categoryId == cat }
    }

    @Test fun scopedToCategory() = runTest {
        val query = MutableStateFlow("minecraft")
        var last: List<VodItem>? = null
        val job = launch { vodMatches(query, MutableStateFlow(Kind.MOVIE to 1L), search = search).collect { last = it } }
        advanceUntilIdle()
        assertEquals(listOf(1L), last!!.map { it.id })
        assertEquals(Triple(Kind.MOVIE, 1L, "minecraft"), calls.single())
        job.cancel()
    }

    @Test fun allWhenNoCategory() = runTest {
        var last: List<VodItem>? = null
        val job = launch { vodMatches(MutableStateFlow("minecraft"), MutableStateFlow(Kind.SERIES to null), search = search).collect { last = it } }
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L), last!!.map { it.id })
        assertEquals(Triple(Kind.SERIES, null, "minecraft"), calls.single())
        job.cancel()
    }

    @Test fun clearedOnKindAndCategoryChange() = runTest {
        val query = MutableStateFlow("")
        val scope = MutableStateFlow<Pair<Kind, Long?>>(Kind.MOVIE to null)
        val job = launch { clearQueryOnScopeChange(scope, query) }
        advanceUntilIdle()
        query.value = "minecraft"; advanceUntilIdle()
        assertEquals("minecraft", query.value) // the first scope doesn't clear
        scope.value = Kind.MOVIE to 5L; advanceUntilIdle()
        assertEquals("", query.value)
        query.value = "doctor"; advanceUntilIdle()
        scope.value = Kind.SERIES to null; advanceUntilIdle()
        assertEquals("", query.value)
        job.cancel()
    }

    @Test fun catalogueChangeRerunsSameQuery() = runTest {
        val version = MutableStateFlow(0L)
        val job = launch { vodMatches(MutableStateFlow("minecraft"), MutableStateFlow(Kind.MOVIE to 1L), version, search).collect { } }
        advanceUntilIdle()
        version.value = 1; advanceUntilIdle()
        assertEquals(2, calls.size)
        job.cancel()
    }

    @Test fun blankQueryNull() = runTest {
        val query = MutableStateFlow("minecraft")
        var last: List<VodItem>? = emptyList()
        val job = launch { vodMatches(query, MutableStateFlow(Kind.MOVIE to 1L), search = search).collect { last = it } }
        advanceUntilIdle()
        query.value = "  "; advanceUntilIdle()
        assertNull(last)
        assertEquals(1, calls.size)
        job.cancel()
    }
}
