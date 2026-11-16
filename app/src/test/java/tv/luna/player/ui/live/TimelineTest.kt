package tv.luna.player.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineTest {
    private val min = 60_000L

    @Test
    fun clipsToWindowInMinutes() {
        val from = 1000 * min
        val to = from + 120 * min
        assertEquals(Span(0, 20), Timeline.span(from - 10 * min, from + 20 * min, from, to))  // started before
        assertEquals(Span(30, 60), Timeline.span(from + 30 * min, from + 90 * min, from, to))
        assertEquals(Span(100, 20), Timeline.span(from + 100 * min, from + 200 * min, from, to)) // runs past end
        assertNull(Timeline.span(to, to + 10 * min, from, to))
    }

    @Test
    fun windowStartsAtPreviousHalfHour() {
        assertEquals(1800_000L * 3, Timeline.windowStart(1800_000L * 3 + 29 * min))
    }

    @Test
    fun returningToTheGuideRefocusesTheWatchedChannel() {
        assertEquals(2, Timeline.rowFor(listOf(10L, 11L, 12L), focusedId = 12L))
        assertEquals(0, Timeline.rowFor(listOf(10L, 11L, 12L), focusedId = 99L)) // other category: start at the top
        assertEquals(0, Timeline.rowFor(listOf(10L), focusedId = null))
    }

    @Test fun okWithoutAutoplayPreviewsThenFullScreen() {
        assertEquals(OkAction.PREVIEW, okAction(false, previewingId = null, channelId = 1))
        assertEquals(OkAction.PREVIEW, okAction(false, previewingId = 2, channelId = 1))
        assertEquals(OkAction.FULL_SCREEN, okAction(false, previewingId = 1, channelId = 1))
    }
    @Test fun okWithAutoplayAlwaysFullScreen() = assertEquals(OkAction.FULL_SCREEN, okAction(true, null, 1))

    @Test fun zapWrapsAndHandlesSingle() {
        assertEquals(2L, zap(listOf(1, 2, 3), 1, +1)); assertEquals(3L, zap(listOf(1, 2, 3), 1, -1))
        assertEquals(1L, zap(listOf(1, 2, 3), 3, +1)); assertEquals(5L, zap(listOf(5), 5, +1))
        assertEquals(1L, zap(listOf(1, 2), 9, +1)); assertNull(zap(emptyList(), 1, +1))
    }

    @Test fun zapInsideRecentsDoesNotReorderThem() {
        assertEquals(false, zapRecordsRecent(tv.luna.player.data.repo.RailEntry.Recent))
        assertEquals(true, zapRecordsRecent(tv.luna.player.data.repo.RailEntry.Favourites))
        assertEquals(true, zapRecordsRecent(null))
    }
}
