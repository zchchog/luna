package tv.luna.player.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    // Own scope, not runTest's: DataStore keeps a coroutine alive that runTest would wait on.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs by lazy { PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("t.preferences_pb") } }
    private val repo by lazy { SettingsRepository(prefs) }

    @After fun tearDown() = scope.cancel()

    @Test fun defaultsMatchSpec() = runTest { assertEquals(AppSettings(), repo.settings.first()) }

    @Test fun updateRoundTripsEveryField() = runTest {
        val s = AppSettings(ThemeId.LUNA, true, AudioDecoder.HARDWARE, false, true, 24, true, false, 14, 0, 42L, true)
            .copy(subtitlesOn = true, subtitleLanguage = "fr")
        repo.update { s }
        assertEquals(s, repo.settings.first())
    }

    @Test fun subtitleLanguageClearsToNull() = runTest {
        repo.update { it.copy(subtitleLanguage = "fr") }
        repo.update { it.copy(subtitleLanguage = null) }
        assertEquals(null, repo.settings.first().subtitleLanguage)
    }

    @Test fun themeRoundTrips() = runTest {
        repo.update { it.copy(theme = ThemeId.LUNA) }
        assertEquals(ThemeId.LUNA, repo.settings.first().theme)
        prefs.edit { it[stringPreferencesKey("theme")] = "MIDNIGHT" }
        assertEquals(ThemeId.LUNA, repo.settings.first().theme)
    }

    @Test fun themeColourRoundTripsAndUnknownReadsDefault() = runTest {
        repo.update { it.copy(themeColour = ThemeColour.TEAL) }
        assertEquals(ThemeColour.TEAL, repo.settings.first().themeColour)
        prefs.edit { it[stringPreferencesKey("theme_colour")] = "nonsense" }
        assertEquals(ThemeColour.DEFAULT, repo.settings.first().themeColour)
    }

    @Test fun outOfRangeValuesAreClamped() = runTest {
        repo.update { it.copy(epgDaysToKeep = 30, epgPastDays = -1, epgUpdateIntervalHours = 5) }
        val s = repo.settings.first()
        assertEquals(14, s.epgDaysToKeep); assertEquals(0, s.epgPastDays); assertEquals(12, s.epgUpdateIntervalHours)
    }

    @Test fun clearingLastChannel() = runTest {
        repo.update { it.copy(lastLiveChannelId = 7) }; repo.update { it.copy(lastLiveChannelId = null) }
        assertNull(repo.settings.first().lastLiveChannelId)
    }

    @Test fun logoAndGuideOrdersRoundTrip() = runTest {
        repo.update { it.copy(logoOrder = listOf(LogoKind.OPEN_SOURCE, LogoKind.PLAYLIST), guideOrder = listOf(GuideKind.OPEN_SOURCE, GuideKind.PLAYLIST), borrowSiblingGuide = false) }
        val s = repo.settings.first()
        assertEquals(listOf(LogoKind.OPEN_SOURCE, LogoKind.PLAYLIST), s.logoOrder)
        assertEquals(listOf(GuideKind.OPEN_SOURCE, GuideKind.PLAYLIST), s.guideOrder); assertFalse(s.borrowSiblingGuide)
    }

    @Test fun defaultsArePlaylistThenOpenSource() = runTest {
        assertEquals(listOf(LogoKind.PLAYLIST, LogoKind.OPEN_SOURCE), repo.settings.first().logoOrder)
        assertEquals(listOf(GuideKind.PLAYLIST, GuideKind.OPEN_SOURCE), repo.settings.first().guideOrder)
    }

    /** Settings saved by the single-choice options before 2026-11-12 keep their meaning. */
    @Test fun oldChoicesMigrate() = runTest {
        prefs.edit { it[stringPreferencesKey("logo_source")] = "MY_PACK_FILLED"; it[stringPreferencesKey("guide_source")] = "EPGSHARE" }
        assertEquals(listOf(LogoKind.PACK, LogoKind.OPEN_SOURCE, LogoKind.PLAYLIST), repo.settings.first().logoOrder)
        assertEquals(listOf(GuideKind.OPEN_SOURCE), repo.settings.first().guideOrder)
        prefs.edit { it[stringPreferencesKey("logo_source")] = "TV_LOGOS"; it[stringPreferencesKey("guide_source")] = "PLAYLIST" }
        assertEquals(listOf(LogoKind.OPEN_SOURCE), repo.settings.first().logoOrder)
        assertEquals(listOf(GuideKind.PLAYLIST), repo.settings.first().guideOrder)
    }

    @Test fun badOrdersFallBack() = runTest {
        prefs.edit { it[stringPreferencesKey("logo_order")] = "BOGUS,,PLAYLIST,PLAYLIST"; it[stringPreferencesKey("guide_order")] = "" }
        assertEquals(listOf(LogoKind.PLAYLIST), repo.settings.first().logoOrder)
        assertEquals(listOf(GuideKind.PLAYLIST, GuideKind.OPEN_SOURCE), repo.settings.first().guideOrder)
    }

    @Test fun hideHeaderChannelsRoundTrip() = runTest {
        assertFalse(repo.settings.first().hideHeaderChannels)
        repo.update { it.copy(hideHeaderChannels = true) }
        assertEquals(true, repo.settings.first().hideHeaderChannels)
    }

    @Test fun recordingSettingsRoundTripAndClamp() = runTest {
        val s = AppSettings(recordingOn = true, recordTo = tv.luna.player.recording.RecStorage.SMB, recordUsbPath = "/storage/AB/x",
            startEarlyMin = 5, endLateMin = 30, keepAwake = false, smbServer = "nas", smbShare = "tv", smbFolder = "Rec", smbUser = "me", smbPassword = "pw")
        repo.update { s }
        assertEquals(s, repo.settings.first())
        repo.update { it.copy(startEarlyMin = 7, endLateMin = 99) }
        repo.settings.first().let { assertEquals(2, it.startEarlyMin); assertEquals(5, it.endLateMin) }
    }
}
