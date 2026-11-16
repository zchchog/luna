package tv.luna.player.data.db

import tv.luna.player.data.security.Secrets
import kotlinx.coroutines.flow.map

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tv.luna.player.data.model.Kind

/** SQLite on older Android caps bound variables at 999; chunk id lists below that. */
internal const val SQL_CHUNK = 900

data class CategoryCount(val categoryId: Long?, val n: Int)
data class GroupCount(val groupId: Long, val n: Int)

@Dao
abstract class ProviderDao {
    // URL, username and password are stored encrypted (Secrets); everything outside this DAO sees plain values.
    @Insert protected abstract suspend fun insertRaw(p: ProviderEntity): Long
    @Update protected abstract suspend fun updateRaw(p: ProviderEntity)
    @Query("SELECT * FROM provider WHERE id = :id") protected abstract suspend fun getRaw(id: Long): ProviderEntity?
    @Query("SELECT * FROM provider WHERE id = :id") protected abstract fun observeRaw(id: Long): Flow<ProviderEntity?>
    @Query("SELECT * FROM provider ORDER BY name") protected abstract fun observeAllRaw(): Flow<List<ProviderEntity>>
    @Query("SELECT * FROM provider ORDER BY name") protected abstract suspend fun allRaw(): List<ProviderEntity>

    suspend fun insert(p: ProviderEntity): Long = insertRaw(p.sealed())
    suspend fun update(p: ProviderEntity) = updateRaw(p.sealed())
    @Delete abstract suspend fun delete(p: ProviderEntity)
    suspend fun get(id: Long): ProviderEntity? = getRaw(id)?.opened()
    fun observe(id: Long): Flow<ProviderEntity?> = observeRaw(id).map { it?.opened() }
    fun observeAll(): Flow<List<ProviderEntity>> = observeAllRaw().map { l -> l.map { it.opened() } }
    suspend fun all(): List<ProviderEntity> = allRaw().map { it.opened() }
    @Query("SELECT id FROM provider ORDER BY name") abstract suspend fun ids(): List<Long>
    /** Rows whose login is still plain text (saved before encryption), as stored. */
    suspend fun unsealed(): List<ProviderEntity> = allRaw().filter { !Secrets.isEncrypted(it.url) }
    @Query("UPDATE provider SET nameStrip = :s WHERE id = :id") abstract suspend fun setNameStrip(id: Long, s: String)
    @Query("UPDATE provider SET categoryStrip = :s WHERE id = :id") abstract suspend fun setCategoryStrip(id: Long, s: String)
    @Query("UPDATE provider SET lastSync = :t WHERE id = :id") abstract suspend fun setLastSync(id: Long, t: Long)
    @Query("UPDATE provider SET maxConnections = :n WHERE id = :id") abstract suspend fun setMaxConnections(id: Long, n: Int?)

    // One-off move of old saved links to login placeholders (ProviderRepository.sealOldRows): [from] = "/user/pass/" as encoded in the URL.
    @Query("UPDATE channel SET streamUrl = replace(streamUrl, :from, :to) WHERE providerId = :pid") abstract suspend fun replaceInChannelUrls(pid: Long, from: String, to: String)
    @Query("UPDATE movie SET streamUrl = replace(streamUrl, :from, :to) WHERE providerId = :pid") abstract suspend fun replaceInMovieUrls(pid: Long, from: String, to: String)
    @Query("UPDATE episode SET streamUrl = replace(streamUrl, :from, :to) WHERE seriesId IN (SELECT id FROM series WHERE providerId = :pid)")
    abstract suspend fun replaceInEpisodeUrls(pid: Long, from: String, to: String)
}


private fun ProviderEntity.sealed() = copy(url = Secrets.encrypt(url)!!, username = Secrets.encrypt(username), password = Secrets.encrypt(password))
private fun ProviderEntity.opened() = copy(url = Secrets.decrypt(url).orEmpty(), username = Secrets.decrypt(username), password = Secrets.decrypt(password))

@Dao
abstract class CategoryDao {
    @Query("SELECT * FROM category WHERE providerId = :providerId AND kind = :kind AND hidden = 0 ORDER BY sortOrder, name")
    abstract fun observeVisible(providerId: Long, kind: Kind): Flow<List<CategoryEntity>>

    /** All categories of a kind for a provider (used by sync to match on remoteId). */
    @Query("SELECT * FROM category WHERE providerId = :providerId AND kind = :kind")
    abstract suspend fun byRemoteIds(providerId: Long, kind: Kind): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE id = :id") abstract suspend fun get(id: Long): CategoryEntity?
    @Upsert abstract suspend fun upsert(list: List<CategoryEntity>)
    @Update abstract suspend fun update(c: CategoryEntity)
    @Update abstract suspend fun update(list: List<CategoryEntity>)

    /** Hidden ones included, for the settings pages. */
    @Query("SELECT * FROM category WHERE providerId = :providerId AND kind = :kind ORDER BY sortOrder, name")
    abstract fun observeAll(providerId: Long, kind: Kind): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM category WHERE providerId = :providerId AND kind = :kind ORDER BY sortOrder, name")
    abstract suspend fun all(providerId: Long, kind: Kind): List<CategoryEntity>
    @Query("SELECT * FROM category WHERE providerId = :providerId") abstract fun observeAllKinds(providerId: Long): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM category WHERE providerId = :providerId") abstract suspend fun allKinds(providerId: Long): List<CategoryEntity>

    @Query("UPDATE category SET hidden = :hidden WHERE id = :id") abstract suspend fun setHidden(id: Long, hidden: Boolean)
    @Query("UPDATE category SET hidden = :hidden WHERE providerId = :providerId AND kind = :kind")
    abstract suspend fun setHiddenForKind(providerId: Long, kind: Kind, hidden: Boolean)
    @Query("UPDATE category SET hidden = :hidden WHERE providerId = :providerId AND country IN (:countries)")
    abstract suspend fun setHiddenForCountries(providerId: Long, countries: List<String>, hidden: Boolean)
    @Query("UPDATE category SET hidden = :hidden WHERE providerId = :providerId AND country IS NULL")
    abstract suspend fun setHiddenForNoCountry(providerId: Long, hidden: Boolean)
    @Query("SELECT DISTINCT country FROM category WHERE providerId = :providerId")
    abstract suspend fun countries(providerId: Long): List<String?>

    @Query("DELETE FROM category WHERE id IN (:ids)")
    protected abstract suspend fun deleteIds(ids: List<Long>)

    @Transaction
    open suspend fun deleteMissing(providerId: Long, kind: Kind, keepRemoteIds: List<String>): List<Long> {
        val keep = keepRemoteIds.toHashSet()
        val gone = byRemoteIds(providerId, kind).filter { it.remoteId !in keep }.map { it.id }
        gone.chunked(SQL_CHUNK).forEach { deleteIds(it) }
        return gone
    }
}

/** A name to search; [number] is the shown channel number (custom first). */
data class SearchRow(val id: Long, val name: String, val customName: String?, val number: Int?, val categoryId: Long?)

@Dao
abstract class ChannelDao {
    @Query(
        """SELECT * FROM channel WHERE categoryId = :categoryId AND hidden = 0
           ORDER BY coalesce(customNumber, number) IS NULL, coalesce(customNumber, number), coalesce(customName, name)"""
    )
    abstract fun observeByCategory(categoryId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT categoryId, count(*) AS n FROM channel WHERE providerId = :providerId AND hidden = 0 GROUP BY categoryId")
    abstract suspend fun counts(providerId: Long): List<CategoryCount>
    @Query("SELECT categoryId, count(*) AS n FROM channel WHERE providerId = :providerId AND hidden = 0 GROUP BY categoryId")
    abstract fun observeCounts(providerId: Long): Flow<List<CategoryCount>>

    @Query("SELECT * FROM channel WHERE providerId = :providerId")
    abstract suspend fun byProvider(providerId: Long): List<ChannelEntity>

    @Query("SELECT * FROM channel WHERE id = :id") abstract suspend fun get(id: Long): ChannelEntity?
    @Query("SELECT * FROM channel WHERE id IN (:ids)") abstract suspend fun byIds(ids: List<Long>): List<ChannelEntity>

    /** Search: visible channels in visible (or no) categories, slim rows. */
    @Query(
        """SELECT ch.id, ch.name, ch.customName, coalesce(ch.customNumber, ch.number) AS number, ch.categoryId FROM channel ch
           LEFT JOIN category c ON c.id = ch.categoryId WHERE ch.providerId = :providerId AND ch.hidden = 0 AND coalesce(c.hidden, 0) = 0"""
    )
    abstract suspend fun searchable(providerId: Long): List<SearchRow>

    @Query("SELECT * FROM channel WHERE providerId = :providerId AND streamUrl = :url LIMIT 1")
    abstract suspend fun byStreamUrl(providerId: Long, url: String): ChannelEntity?

    @Upsert abstract suspend fun upsert(list: List<ChannelEntity>)
    /** Provider columns only, so a sync never writes back a stale copy of the user's own columns. */
    @Update(entity = ChannelEntity::class) abstract suspend fun updateFromProvider(list: List<ChannelFromProvider>)
    @Update abstract suspend fun update(c: ChannelEntity)
    @Query("UPDATE channel SET builtinLogo = :path WHERE id = :id") abstract suspend fun setBuiltinLogo(id: Long, path: String?)
    @Query("""SELECT ch.* FROM channel ch JOIN epg_link l ON l.channelId = ch.id LEFT JOIN category cat ON cat.id = ch.categoryId
              WHERE ch.providerId = :p AND ch.hidden = 0 AND (cat.hidden IS NULL OR cat.hidden = 0)""")
    abstract suspend fun withGuide(p: Long): List<ChannelEntity>
    @Query("UPDATE channel SET customLogo = :logo WHERE id = :id") abstract suspend fun setCustomLogo(id: Long, logo: String?)
    @Query("SELECT customLogo FROM channel WHERE customLogo IS NOT NULL") abstract suspend fun customLogos(): List<String>
    @Query("UPDATE channel SET packLogo = :path WHERE id = :id") abstract suspend fun setPackLogo(id: Long, path: String?)
    @Query("UPDATE channel SET packLogo = NULL WHERE providerId = :p") abstract suspend fun clearPackLogos(p: Long)
    @Query("SELECT count(DISTINCT packLogo) FROM channel WHERE providerId = :p AND packLogo IS NOT NULL")
    abstract fun observePackCount(p: Long): Flow<Int>
    @Query(
        """SELECT ch.* FROM channel ch JOIN favourite f ON f.itemId = ch.id AND f.providerId = ch.providerId AND f.kind = 'LIVE'
           WHERE ch.providerId = :providerId AND ch.hidden = 0 ORDER BY f.addedAt DESC"""
    )
    abstract fun observeFavourites(providerId: Long): Flow<List<ChannelEntity>>
    @Query("UPDATE channel SET hidden = :hidden WHERE id = :id") abstract suspend fun setHidden(id: Long, hidden: Boolean)
    @Query("UPDATE channel SET customName = :name WHERE id = :id") abstract suspend fun setCustomName(id: Long, name: String?)

    @Query("DELETE FROM channel WHERE id IN (:ids)")
    abstract suspend fun deleteIds(ids: List<Long>)

    /** Deletes channels whose remoteId is gone; returns the deleted row ids. */
    @Transaction
    open suspend fun deleteMissing(providerId: Long, keepRemoteIds: List<String>): List<Long> {
        val keep = keepRemoteIds.toHashSet()
        val gone = byProvider(providerId).filter { it.remoteId !in keep }.map { it.id }
        gone.chunked(SQL_CHUNK).forEach { deleteIds(it) }
        return gone
    }
}

@Dao
abstract class MovieDao {
    @Update(entity = MovieEntity::class) abstract suspend fun updateFromProvider(list: List<MovieFromProvider>)
    @Query("SELECT * FROM movie WHERE providerId = :providerId")
    abstract suspend fun byProvider(providerId: Long): List<MovieEntity>
    @Query("SELECT * FROM movie WHERE categoryId = :categoryId ORDER BY name")
    abstract fun observeByCategory(categoryId: Long): Flow<List<MovieEntity>>
    @Query("SELECT * FROM movie WHERE categoryId = :categoryId ORDER BY name LIMIT :limit")
    abstract suspend fun inCategory(categoryId: Long, limit: Int): List<MovieEntity>
    @Query("SELECT * FROM movie WHERE id IN (:ids)") abstract suspend fun byIds(ids: List<Long>): List<MovieEntity>
    @Query(
        """SELECT m.id, m.name, NULL AS customName, NULL AS number, m.categoryId FROM movie m
           LEFT JOIN category c ON c.id = m.categoryId WHERE m.providerId = :providerId AND coalesce(c.hidden, 0) = 0"""
    )
    abstract suspend fun searchable(providerId: Long): List<SearchRow>
    @Query("SELECT categoryId, count(*) AS n FROM movie WHERE providerId = :providerId GROUP BY categoryId")
    abstract suspend fun counts(providerId: Long): List<CategoryCount>
    @Query("SELECT categoryId, count(*) AS n FROM movie WHERE providerId = :providerId GROUP BY categoryId")
    abstract fun observeCounts(providerId: Long): Flow<List<CategoryCount>>
    @Upsert abstract suspend fun upsert(list: List<MovieEntity>)

    @Query("DELETE FROM movie WHERE id IN (:ids)")
    abstract suspend fun deleteIds(ids: List<Long>)

    @Transaction
    open suspend fun deleteMissing(providerId: Long, keepRemoteIds: List<String>): List<Long> {
        val keep = keepRemoteIds.toHashSet()
        val gone = byProvider(providerId).filter { it.remoteId !in keep }.map { it.id }
        gone.chunked(SQL_CHUNK).forEach { deleteIds(it) }
        return gone
    }
}

@Dao
abstract class SeriesDao {
    @Update(entity = SeriesEntity::class) abstract suspend fun updateFromProvider(list: List<SeriesFromProvider>)
    @Query("SELECT * FROM series WHERE providerId = :providerId")
    abstract suspend fun byProvider(providerId: Long): List<SeriesEntity>
    @Query("SELECT * FROM series WHERE categoryId = :categoryId ORDER BY name")
    abstract fun observeByCategory(categoryId: Long): Flow<List<SeriesEntity>>
    @Query("SELECT * FROM series WHERE categoryId = :categoryId ORDER BY name LIMIT :limit")
    abstract suspend fun inCategory(categoryId: Long, limit: Int): List<SeriesEntity>
    @Query("SELECT * FROM series WHERE id IN (:ids)") abstract suspend fun byIds(ids: List<Long>): List<SeriesEntity>
    @Query(
        """SELECT s.id, s.name, NULL AS customName, NULL AS number, s.categoryId FROM series s
           LEFT JOIN category c ON c.id = s.categoryId WHERE s.providerId = :providerId AND coalesce(c.hidden, 0) = 0"""
    )
    abstract suspend fun searchable(providerId: Long): List<SearchRow>
    @Query("SELECT categoryId, count(*) AS n FROM series WHERE providerId = :providerId GROUP BY categoryId")
    abstract suspend fun counts(providerId: Long): List<CategoryCount>
    @Query("SELECT categoryId, count(*) AS n FROM series WHERE providerId = :providerId GROUP BY categoryId")
    abstract fun observeCounts(providerId: Long): Flow<List<CategoryCount>>
    @Upsert abstract suspend fun upsert(list: List<SeriesEntity>)

    @Query("SELECT * FROM episode WHERE seriesId = :seriesId ORDER BY season, number")
    abstract suspend fun episodes(seriesId: Long): List<EpisodeEntity>
    @Upsert abstract suspend fun upsertEpisodes(list: List<EpisodeEntity>)
    @Query("SELECT id FROM episode WHERE seriesId IN (:seriesIds)")
    protected abstract suspend fun episodeIdsChunk(seriesIds: List<Long>): List<Long>
    open suspend fun episodeIds(seriesIds: List<Long>): List<Long> = seriesIds.chunked(SQL_CHUNK).flatMap { episodeIdsChunk(it) }

    @Query("DELETE FROM series WHERE id IN (:ids)")
    abstract suspend fun deleteIds(ids: List<Long>)

    @Transaction
    open suspend fun deleteMissing(providerId: Long, keepRemoteIds: List<String>): List<Long> {
        val keep = keepRemoteIds.toHashSet()
        val gone = byProvider(providerId).filter { it.remoteId !in keep }.map { it.id }
        gone.chunked(SQL_CHUNK).forEach { deleteIds(it) }
        return gone
    }
}

@Dao
abstract class ProgrammeDao {
    @Query(
        """SELECT * FROM programme WHERE epgChannelKey IN (:keys) AND stop > :from AND start < :to
           AND gen = (SELECT gen FROM epg_source WHERE id = programme.sourceId)
           ORDER BY epgChannelKey, start"""
    )
    abstract suspend fun window(keys: List<String>, from: Long, to: Long): List<ProgrammeEntity>

    @Insert abstract suspend fun insertAll(list: List<ProgrammeEntity>)
    /** For use inside [androidx.room.RoomDatabase.runInTransaction] on a background thread. */
    @Insert abstract fun insertAllBlocking(list: List<ProgrammeEntity>)
    @Query("DELETE FROM programme WHERE sourceId = :sourceId") abstract fun deleteForSourceBlocking(sourceId: Long)
    /** Up to [limit] rows of [sourceId] from generations other than [keep]; returns how many went. Small, so short. */
    @Query("DELETE FROM programme WHERE id IN (SELECT id FROM programme WHERE sourceId = :sourceId AND gen != :keep LIMIT :limit)")
    abstract fun deleteOtherGensBlocking(sourceId: Long, keep: Int, limit: Int): Int
    @Query("DELETE FROM programme WHERE sourceId = :sourceId") abstract suspend fun deleteForSource(sourceId: Long)
    /** Deletes programmes that ended before [t]. */
    @Query("DELETE FROM programme WHERE stop < :t") abstract suspend fun deleteBefore(t: Long)
    @Query("SELECT count(*) FROM programme") abstract suspend fun count(): Int
    /** Guide channels ("sourceId|xmltvId") with a programme that hasn't finished yet. */
    @Query("SELECT DISTINCT epgChannelKey FROM programme WHERE stop > :now") abstract suspend fun liveKeys(now: Long): List<String>
    @Query("DELETE FROM programme") abstract suspend fun deleteAll()
}

@Dao
interface EpgDao {
    @Insert suspend fun insertSource(s: EpgSourceEntity): Long
    @Update suspend fun updateSource(s: EpgSourceEntity)
    @Query("SELECT * FROM epg_source WHERE providerId = :providerId ORDER BY priority")
    suspend fun sources(providerId: Long): List<EpgSourceEntity>
    @Query("SELECT * FROM epg_source WHERE providerId = :providerId ORDER BY priority")
    fun observeSources(providerId: Long): Flow<List<EpgSourceEntity>>
    @Query("SELECT * FROM epg_source WHERE id = :id") suspend fun source(id: Long): EpgSourceEntity?
    @Query("DELETE FROM epg_source WHERE id = :id") suspend fun deleteSource(id: Long)
    @Query("DELETE FROM epg_link WHERE sourceId = :sourceId") suspend fun deleteLinksForSource(sourceId: Long)
    @Query("UPDATE epg_source SET name = :name WHERE id = :id") suspend fun renameSource(id: Long, name: String?)
    @Query("UPDATE epg_source SET offsetMinutes = :minutes WHERE id = :id") suspend fun setOffset(id: Long, minutes: Int)
    @Query("UPDATE epg_source SET url = :url WHERE id = :id") suspend fun setUrl(id: Long, url: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertChannels(list: List<EpgChannelEntity>)
    @Query("DELETE FROM epg_channel WHERE sourceId = :sourceId") suspend fun deleteChannels(sourceId: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun insertChannelsBlocking(list: List<EpgChannelEntity>)
    @Query("DELETE FROM epg_channel WHERE sourceId = :sourceId") fun deleteChannelsBlocking(sourceId: Long)
    @Query("UPDATE epg_source SET lastFetched = :t WHERE id = :id") fun setLastFetchedBlocking(id: Long, t: Long)
    @Query("UPDATE epg_source SET gen = :gen, lastFetched = :t WHERE id = :id") fun setGenBlocking(id: Long, gen: Int, t: Long)
    @Query("SELECT * FROM epg_channel WHERE sourceId = :sourceId") suspend fun channels(sourceId: Long): List<EpgChannelEntity>

    @Query("SELECT * FROM epg_link WHERE channelId IN (SELECT id FROM channel WHERE providerId = :providerId)")
    suspend fun links(providerId: Long): List<EpgLinkEntity>
    @Query("SELECT * FROM epg_link WHERE channelId = :channelId") suspend fun link(channelId: Long): EpgLinkEntity?
    @Query("DELETE FROM epg_link WHERE channelId = :id") suspend fun deleteLink(id: Long)
    @Query("SELECT * FROM epg_link WHERE channelId IN (:ids)") suspend fun linksFor(ids: List<Long>): List<EpgLinkEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertLinks(list: List<EpgLinkEntity>)
    /** Emits whenever an import (lastFetched) or a rematch (epg_link) finishes, so open guides reload. */
    // Offsets are in it so a changed source offset re-reads the guide without a refresh.
    @Query("SELECT coalesce((SELECT max(lastFetched) FROM epg_source), 0) + (SELECT count(*) FROM epg_link) + (SELECT coalesce(sum(offsetMinutes), 0) FROM epg_source)")
    fun observeGuideVersion(): Flow<Long>
    @Query("DELETE FROM epg_link WHERE manual = 0 AND channelId IN (SELECT id FROM channel WHERE providerId = :providerId)")
    suspend fun deleteAutoLinks(providerId: Long)
    @Query("DELETE FROM epg_link WHERE manual = 0") suspend fun deleteAllAutoLinks()
    @Query("DELETE FROM epg_channel") suspend fun deleteAllChannels()
    @Query("UPDATE epg_source SET lastStatus = :status WHERE id = :id") suspend fun setStatus(id: Long, status: String?)
    @Query("UPDATE epg_source SET lastStatus = NULL, lastFetched = NULL") suspend fun clearAllStatus()
}

@Dao
interface RuleDao {
    @Insert suspend fun insertNameRules(list: List<NameRuleEntity>)
    @Query("SELECT * FROM name_rule WHERE providerId = :providerId ORDER BY ruleOrder")
    suspend fun nameRules(providerId: Long): List<NameRuleEntity>
    @Insert suspend fun insertHideRule(r: HideRuleEntity): Long
    @Query("SELECT * FROM hide_rule WHERE providerId = :providerId AND kind = :kind")
    suspend fun hideRules(providerId: Long, kind: Kind): List<HideRuleEntity>
}

@Dao
abstract class ProgressDao {
    @Upsert abstract suspend fun upsert(p: ProgressEntity)
    @Query("DELETE FROM progress WHERE providerId = :providerId") abstract suspend fun deleteForProvider(providerId: Long)
    @Query("SELECT * FROM progress WHERE providerId = :providerId ORDER BY updatedAt DESC")
    abstract suspend fun all(providerId: Long): List<ProgressEntity>

    @Query("DELETE FROM progress WHERE providerId = :providerId AND kind = :kind AND itemId IN (:ids)")
    protected abstract suspend fun deleteIds(providerId: Long, kind: Kind, ids: List<Long>)

    @Transaction
    open suspend fun deleteForItems(providerId: Long, kind: Kind, ids: List<Long>) {
        ids.chunked(SQL_CHUNK).forEach { deleteIds(providerId, kind, it) }
    }
}

@Dao
abstract class FavouriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun add(f: FavouriteEntity)
    @Query("DELETE FROM favourite WHERE providerId = :providerId") abstract suspend fun deleteForProvider(providerId: Long)
    @Query("SELECT * FROM favourite WHERE providerId = :providerId AND kind = :kind ORDER BY addedAt DESC")
    abstract suspend fun list(providerId: Long, kind: Kind): List<FavouriteEntity>
    @Query("SELECT * FROM favourite WHERE providerId = :providerId AND kind = :kind ORDER BY addedAt DESC")
    abstract fun observe(providerId: Long, kind: Kind): Flow<List<FavouriteEntity>>
    @Query("DELETE FROM favourite WHERE providerId = :providerId AND kind = :kind AND itemId = :itemId")
    abstract suspend fun remove(providerId: Long, kind: Kind, itemId: Long)

    @Query("DELETE FROM favourite WHERE providerId = :providerId AND kind = :kind AND itemId IN (:ids)")
    protected abstract suspend fun deleteIds(providerId: Long, kind: Kind, ids: List<Long>)

    /** Removes favourites of items deleted by sync (no FK: itemId is polymorphic). */
    @Transaction
    open suspend fun deleteForItems(providerId: Long, kind: Kind, ids: List<Long>) {
        ids.chunked(SQL_CHUNK).forEach { deleteIds(providerId, kind, it) }
    }
}

@Dao
abstract class CustomGroupDao {
    @Insert abstract suspend fun insert(g: CustomGroupEntity): Long
    @Query("SELECT coalesce(max(sortOrder) + 1, 0) FROM custom_group WHERE providerId = :providerId")
    protected abstract suspend fun nextOrder(providerId: Long): Int

    /** New user category at the end of the user list. */
    @Transaction
    open suspend fun create(providerId: Long, name: String): Long =
        insert(CustomGroupEntity(providerId = providerId, name = name, sortOrder = nextOrder(providerId)))

    @Query("UPDATE custom_group SET name = :name WHERE id = :id") abstract suspend fun rename(id: Long, name: String)
    @Query("UPDATE custom_group SET placement = :placement WHERE id = :id") abstract suspend fun setPlacement(id: Long, placement: GroupPlacement)
    @Query("UPDATE custom_group SET sortOrder = :sortOrder WHERE id = :id") abstract suspend fun setSortOrder(id: Long, sortOrder: Int)
    @Query("DELETE FROM custom_group WHERE id = :id") abstract suspend fun delete(id: Long)
    @Query("SELECT * FROM custom_group WHERE providerId = :providerId ORDER BY sortOrder, name")
    abstract fun observe(providerId: Long): Flow<List<CustomGroupEntity>>

    @Query(
        """SELECT gc.groupId, count(*) AS n FROM custom_group_channel gc JOIN channel ch ON ch.id = gc.channelId AND ch.hidden = 0
           JOIN custom_group g ON g.id = gc.groupId WHERE g.providerId = :providerId GROUP BY gc.groupId"""
    )
    abstract fun observeCounts(providerId: Long): Flow<List<GroupCount>>

    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract suspend fun insertMember(m: CustomGroupChannelEntity)
    @Query("SELECT coalesce(max(sortOrder) + 1, 0) FROM custom_group_channel WHERE groupId = :groupId")
    protected abstract suspend fun nextMemberOrder(groupId: Long): Int

    /** Appends to the group; a channel already in it keeps its place. */
    @Transaction
    open suspend fun addChannel(groupId: Long, channelId: Long) =
        insertMember(CustomGroupChannelEntity(groupId, channelId, nextMemberOrder(groupId)))

    @Query("DELETE FROM custom_group_channel WHERE groupId = :groupId AND channelId = :channelId")
    abstract suspend fun removeChannel(groupId: Long, channelId: Long)

    @Query(
        """SELECT ch.* FROM channel ch JOIN custom_group_channel gc ON gc.channelId = ch.id
           WHERE gc.groupId = :groupId AND ch.hidden = 0 ORDER BY gc.sortOrder"""
    )
    abstract fun observeChannels(groupId: Long): Flow<List<ChannelEntity>>

    /** Every member in group order, hidden ones included (backup). */
    @Query("SELECT ch.remoteId FROM channel ch JOIN custom_group_channel gc ON gc.channelId = ch.id WHERE gc.groupId = :groupId ORDER BY gc.sortOrder")
    abstract suspend fun memberRemoteIds(groupId: Long): List<String>

    @Query("SELECT groupId FROM custom_group_channel WHERE channelId = :channelId") abstract suspend fun groupsOf(channelId: Long): List<Long>
}

const val RECENT_CAP = 8

@Dao
abstract class RecentDao {
    @Upsert protected abstract suspend fun upsert(r: RecentChannelEntity)

    @Query(
        // Hidden channels don't count towards the cap (Recents doesn't show them); their rows go too.
        """DELETE FROM recent_channel WHERE providerId = :providerId AND channelId NOT IN
           (SELECT r.channelId FROM recent_channel r JOIN channel ch ON ch.id = r.channelId
            WHERE r.providerId = :providerId AND ch.hidden = 0 ORDER BY r.watchedAt DESC LIMIT :cap)"""
    )
    protected abstract suspend fun trim(providerId: Long, cap: Int)

    /** Records a watch; a re-watch moves the channel to the front, and only the newest [RECENT_CAP] are kept. */
    @Transaction
    open suspend fun record(providerId: Long, channelId: Long, at: Long) {
        upsert(RecentChannelEntity(providerId, channelId, at))
        trim(providerId, RECENT_CAP)
    }

    @Query(
        """SELECT ch.* FROM channel ch JOIN recent_channel r ON r.channelId = ch.id AND r.providerId = ch.providerId
           WHERE ch.providerId = :providerId AND ch.hidden = 0 ORDER BY r.watchedAt DESC"""
    )
    abstract fun observe(providerId: Long): Flow<List<ChannelEntity>>
}

data class ChannelFromProvider(val id: Long, val name: String, val logo: String?, val number: Int?, val tvgId: String?, val streamUrl: String, val categoryId: Long?)
data class MovieFromProvider(val id: Long, val name: String, val poster: String?, val year: Int?, val rating: Double?, val streamUrl: String?, val categoryId: Long?)
data class SeriesFromProvider(val id: Long, val name: String, val poster: String?, val year: Int?, val rating: Double?, val categoryId: Long?)
