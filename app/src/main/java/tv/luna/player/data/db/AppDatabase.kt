package tv.luna.player.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    version = 7,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7)], // 4: (providerId, categoryId) on movie/series for fast category counts
    entities = [
        ProviderEntity::class, CategoryEntity::class, ChannelEntity::class, MovieEntity::class,
        SeriesEntity::class, EpisodeEntity::class, EpgSourceEntity::class, EpgChannelEntity::class,
        ProgrammeEntity::class, EpgLinkEntity::class, NameRuleEntity::class, HideRuleEntity::class,
        FavouriteEntity::class, ProgressEntity::class, RecentChannelEntity::class, ReminderEntity::class,
        CustomGroupEntity::class, CustomGroupChannelEntity::class, tv.luna.player.recording.RecordingEntity::class,
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun categoryDao(): CategoryDao
    abstract fun channelDao(): ChannelDao
    abstract fun movieDao(): MovieDao
    abstract fun seriesDao(): SeriesDao
    abstract fun programmeDao(): ProgrammeDao
    abstract fun epgDao(): EpgDao
    abstract fun ruleDao(): RuleDao
    abstract fun favouriteDao(): FavouriteDao
    abstract fun progressDao(): ProgressDao
    abstract fun customGroupDao(): CustomGroupDao
    abstract fun recentDao(): RecentDao
    abstract fun recordingDao(): tv.luna.player.recording.RecordingDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "iptv.db").build()
    }
}
