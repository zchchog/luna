package tv.luna.player.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun createMakesUniqueFilesAndDeleteRemoves() {
        val dir = File(tmp.root, "rec") // created on demand
        val store = FileStore(RecStorage.INTERNAL, dir)
        val (a, outA) = store.create("x.ts"); outA.use { it.write(1) }
        val (b, outB) = store.create("x.ts"); outB.use { it.write(2) }
        assertEquals(File(dir, "x.ts").path, a)
        assertEquals(File(dir, "x (2).ts").path, b)
        store.delete(a)
        assertFalse(File(a).exists()); assertTrue(File(b).exists())
        assertNotNull(store.freeBytes())
    }

    @Test fun freeSpaceText() {
        assertEquals("About 4.0 GB free ≈ 2 h HD", freeSpaceText(4_000_000_000))
        assertEquals("About 3.8 GB free ≈ 1 h 54 min HD", freeSpaceText(3_800_000_000))
        assertEquals("About 0.5 GB free ≈ 15 min HD", freeSpaceText(500_000_000))
        assertEquals("Free space unknown", freeSpaceText(null))
    }
}
