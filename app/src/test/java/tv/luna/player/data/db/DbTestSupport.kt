package tv.luna.player.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import tv.luna.player.data.model.StreamFormat

fun inMemoryDb(): AppDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

suspend fun AppDatabase.insertTestProvider(name: String = "p"): Long =
    providerDao().insert(
        ProviderEntity(
            name = name, type = ProviderType.M3U, url = "http://x/list.m3u",
            username = null, password = null, userAgent = null,
            streamFormat = StreamFormat.TS, epgOverrideUrls = emptyList(), lastSync = null,
        )
    )
