package tv.luna.player.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs without an AndroidKeyStore, like a box whose keystore is broken or was wiped. */
@RunWith(RobolectricTestRunner::class)
class SecretsTest {
    @Test fun unreadableValueReadsAsMissingInsteadOfCrashing() = assertNull(Secrets.decrypt("enc1:AAAAAAAAAAAAAAAAAAAAAAAAAAAA"))

    @Test fun plainValuesPassThrough() {
        assertEquals("user", Secrets.decrypt("user"))
        assertEquals("user", Secrets.encrypt("user")) // no keystore: stays plain rather than failing the save
    }
}
