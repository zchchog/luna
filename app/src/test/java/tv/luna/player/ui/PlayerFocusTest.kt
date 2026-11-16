package tv.luna.player.ui

import android.app.Activity
import androidx.media3.ui.PlayerView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerFocusTest {
    private fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))

    /** Controls hiding while focus is on the subtitles icon or cog must leave the D-pad working (focus on the player). */
    @Test
    fun hidingControlsKeepsFocusOnThePlayer() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = PlayerView(activity)
        activity.setContentView(view)
        keepFocusWhenControlsHide(view)
        view.showController()
        idle()
        val cog = view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)
        cog.isFocusable = true
        cog.requestFocus()
        view.hideController()
        idle() // the controls fade out before they report hidden
        assertTrue(view.hasFocus())
    }
}
