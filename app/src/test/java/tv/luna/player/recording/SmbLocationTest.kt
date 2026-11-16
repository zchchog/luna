package tv.luna.player.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbLocationTest {
    @Test fun parsesServerShareAndPath() = assertEquals(Triple("nas", "tv", "Recordings/a b.ts"), parseSmbLocation("smb://nas/tv/Recordings/a b.ts"))
    @Test fun topOfShare() = assertEquals(Triple("192.168.1.20", "tv", "a.ts"), parseSmbLocation("smb://192.168.1.20/tv/a.ts"))
    @Test fun badLocationIsNull() { assertNull(parseSmbLocation("/data/x.ts")); assertNull(parseSmbLocation("smb://nas")); assertNull(parseSmbLocation("smb://nas/tv")) }
    @Test fun locationRoundTrip() = assertEquals("smb://nas/tv/Rec/x.ts", smbLocation("nas", "tv", "Rec/x.ts"))
}
