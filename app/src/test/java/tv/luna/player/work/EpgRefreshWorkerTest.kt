package tv.luna.player.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgRefreshWorkerTest {
    @Test
    fun retriesOnlyWhenEverySourceFailed() {
        assertTrue(EpgRefreshWorker.shouldRetry(attempted = 2, failed = 2))
        assertFalse(EpgRefreshWorker.shouldRetry(attempted = 3, failed = 1)) // one dead feed mustn't re-import the good ones
        assertFalse(EpgRefreshWorker.shouldRetry(attempted = 0, failed = 0))
    }
}
