package tv.luna.player.ui.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.luna.player.data.search.SearchResults

class SearchViewModelTest {
    @Test fun latestQueryWins() = runTest {
        val query = MutableStateFlow("")
        val asked = mutableListOf<String>()
        val seen = mutableListOf<SearchResults?>()
        val job = launch { searchFlow(query) { q -> asked += q; SearchResults.EMPTY }.collect { seen += it } }
        advanceUntilIdle()
        query.value = "a"; advanceTimeBy(100)
        query.value = "ab"; advanceTimeBy(100)
        query.value = "abc"; advanceTimeBy(400)
        assertEquals(listOf("abc"), asked)
        job.cancel()
    }

    @Test fun slowSearchIsCancelledByANewQuery() = runTest {
        val query = MutableStateFlow("")
        val done = mutableListOf<String>()
        val job = launch {
            searchFlow(query) { q -> kotlinx.coroutines.delay(1_000); done += q; SearchResults.EMPTY }.collect { }
        }
        query.value = "bbc"; advanceTimeBy(500)
        query.value = "bbc one"; advanceUntilIdle()
        assertEquals(listOf("bbc one"), done)
        job.cancel()
    }

    @Test fun catalogueChangeRerunsSameQuery() = runTest {
        val query = MutableStateFlow("bbc")
        val version = MutableStateFlow(0L)
        var calls = 0
        val job = launch { searchFlow(query, version) { calls++; SearchResults.EMPTY }.collect { } }
        advanceUntilIdle()
        assertEquals(1, calls)
        version.value = 1; advanceUntilIdle() // a channel was hidden while the results were on screen
        assertEquals(2, calls)
        job.cancel()
    }

    @Test fun blankShowsNothing() = runTest {
        val query = MutableStateFlow("bbc")
        var last: SearchResults? = SearchResults.EMPTY
        var calls = 0
        val job = launch { searchFlow(query) { calls++; SearchResults.EMPTY }.collect { last = it } }
        advanceUntilIdle()
        query.value = "  "; advanceUntilIdle()
        assertNull(last)
        assertEquals(1, calls)
        job.cancel()
    }
}
