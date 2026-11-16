package tv.luna.player.data.security

import org.junit.Assert.assertEquals
import org.junit.Test

class WithLoginTest {
    private val login = Login("jo hn", "p/ss")

    @Test fun fillsPathPlaceholders() =
        assertEquals("http://h:8080/live/jo%20hn/p%2Fss/1.ts", withLogin("http://h:8080/live/~iptvu~/~iptvp~/1.ts", login))

    @Test fun fillsEncodedQueryPlaceholders() =
        assertEquals("http://h/xmltv.php?username=jo%20hn&password=p%2Fss",
            withLogin("http://h/xmltv.php?username=%7Eiptvu%7E&password=%7Eiptvp%7E", login))

    @Test fun leavesOtherLinksAlone() {
        assertEquals("http://h/a/b.ts", withLogin("http://h/a/b.ts", login))
        assertEquals("http://h/live/~iptvu~/~iptvp~/1.ts", withLogin("http://h/live/~iptvu~/~iptvp~/1.ts", null))
    }

    @Test fun roundTripsOldLinks() {
        val old = "http://h/live/jo%20hn/p%2Fss/1.ts"
        assertEquals(old, withLogin(withoutLogin(old, login), login))
        assertEquals("http://h/live/~iptvu~/~iptvp~/1.ts", withoutLogin(old, login))
    }

    @Test fun urlHoldsAnyMatchesWholeDecodedParts() {
        val login = listOf("al", "p@ss word")
        assertEquals(true, urlHoldsAny("http://h/xmltv.php?username=al&password=p%40ss%20word", login))
        assertEquals(true, urlHoldsAny("http://h/al/x/epg.xml", login))
        assertEquals(false, urlHoldsAny("http://h/global/channels.xml", login)) // "al" inside a word isn't the login
        val long = listOf("jon", "S3cretPw")
        assertEquals(true, urlHoldsAny("http://h/epg/jon/S3cretPw.xml", long))
        assertEquals(true, urlHoldsAny("http://h/epg.xml?auth=jon:S3cretPw", long))
    }
}
