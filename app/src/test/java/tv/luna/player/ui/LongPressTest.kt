package tv.luna.player.ui

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LongPressTest {
    private fun ev(action: Int, code: Int, repeat: Int) = KeyEvent(0, 0, action, code, repeat)

    @Test fun longOkOnlyOnRepeat() {
        assertFalse(isLongOk(ev(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0)))
        assertTrue(isLongOk(ev(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1)))
        assertTrue(isLongOk(ev(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 1)))
        assertFalse(isLongOk(ev(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 1)))
        assertFalse(isLongOk(ev(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 1)))
    }
}
