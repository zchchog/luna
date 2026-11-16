package org.imdtv.iptvtv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StartStackTest {
    /** The app always opens on the main page and never straight into a channel, whatever was watched last. */
    @Test fun opensOnTheGuide() = assertEquals(listOf(Screen.Live), startStack(true))

    @Test fun noProviderShowsSetup() = assertEquals(listOf(Screen.Setup()), startStack(false))
}
