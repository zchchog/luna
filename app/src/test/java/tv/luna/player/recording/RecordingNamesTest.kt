package tv.luna.player.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class RecordingNamesTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private fun at(s: String) = LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val t = at("2026-11-15T18:00")

    @Test fun plainName() = assertEquals("BBC One - News at Six - 2026-11-15 1800.ts", recordingFileName("BBC One", "News at Six", t, utc))

    @Test fun unsafeCharactersAreRemoved() {
        val n = recordingFileName("A/B: C", "Why? \"Now\" <1|2> *\\", t, utc)
        assertFalse(n, n.any { it in "/\\:*?\"<>|" })
        assertTrue(n.endsWith(".ts"))
    }

    @Test fun longChannelAndTitleStayWithin120() = assertTrue(recordingFileName("c".repeat(300), "x".repeat(300), t, utc).length <= 120)

    @Test fun emojiAtCutBoundaryIsNotSplit() {
        val n = recordingFileName("C", "a".repeat(44) + "😀b", t, utc)
        assertFalse(n, n.any { it.isSurrogate() })
        assertTrue(n, n.contains("a".repeat(44) + " - "))
    }

    @Test fun emptyTitleUsesRecording() = assertTrue(recordingFileName("C", "  ", t, utc).contains(" - Recording - "))

    @Test fun noLeadingDotOrTrailingSpace() {
        val n = recordingFileName(".hidden ", "t.", t, utc)
        assertFalse(n, n.startsWith("."))
        assertEquals("hidden - t - 2026-11-15 1800.ts", n)
    }

    @Test fun clashGetsNumber() {
        assertEquals("a (2).ts", uniqueName("a.ts") { it == "a.ts" })
        assertEquals("a (3).ts", uniqueName("a.ts") { it == "a.ts" || it == "a (2).ts" })
        assertEquals("a.ts", uniqueName("a.ts") { false })
    }
}
