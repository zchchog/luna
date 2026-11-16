package tv.luna.player.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val DB = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun v1ToV2KeepsRowsAndAddsColumns() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync) VALUES (1,'p','XTREAM','http://x','u','pw',NULL,'TS','',NULL)")
            db.execSQL("INSERT INTO category (id,providerId,kind,remoteId,name,sortOrder,hidden,locked,pinned) VALUES (1,1,'LIVE','10','UK| News',3,1,0,0)")
            db.execSQL("INSERT INTO epg_source (id,providerId,url,priority,offsetMinutes,lastFetched) VALUES (1,1,'http://e',0,60,NULL)")
        }
        helper.runMigrationsAndValidate(DB, 2, true).use { db ->
            db.query("SELECT hidden, sortOrder, country FROM category").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)); assertEquals(3, c.getInt(1)); assertTrue(c.isNull(2)) }
            db.query("SELECT nameStrip, username FROM provider").use { c -> c.moveToFirst(); assertEquals("", c.getString(0)); assertEquals("u", c.getString(1)) }
            db.query("SELECT offsetMinutes, name, lastStatus FROM epg_source").use { c -> c.moveToFirst(); assertEquals(60, c.getInt(0)); assertTrue(c.isNull(1)); assertTrue(c.isNull(2)) }
        }
    }

    @Test fun v2ToV3KeepsRowsAndAddsColumns() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync,nameStrip) VALUES (1,'p','XTREAM','http://x','u','pw',NULL,'TS','',NULL,'')")
            db.execSQL("INSERT INTO category (id,providerId,kind,remoteId,name,sortOrder,hidden,locked,pinned,country) VALUES (1,1,'LIVE','10','UK| News',3,1,0,0,'GB')")
            db.execSQL("INSERT INTO channel (id,providerId,categoryId,remoteId,name,customName,logo,customLogo,number,customNumber,hidden,tvgId,epgChannelId,epgOffsetMinutes,streamUrl) VALUES (1,1,1,'101','BBC',NULL,'l.png','mine.png',1,NULL,0,NULL,NULL,NULL,'http://s')")
            db.execSQL("INSERT INTO epg_source (id,providerId,url,priority,offsetMinutes,lastFetched,name,lastStatus) VALUES (1,1,'http://e',0,60,NULL,NULL,NULL)")
        }
        helper.runMigrationsAndValidate(DB, 3, true).use { db ->
            db.query("SELECT customLogo, packLogo, builtinLogo FROM channel").use { c -> c.moveToFirst(); assertEquals("mine.png", c.getString(0)); assertTrue(c.isNull(1)); assertTrue(c.isNull(2)) }
            db.query("SELECT origin, offsetMinutes FROM epg_source").use { c -> c.moveToFirst(); assertEquals("PLAYLIST", c.getString(0)); assertEquals(60, c.getInt(1)) }
        }
    }

    @Test fun v4ToV5AddsColumns() {
        helper.createDatabase(DB, 4).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync,nameStrip) VALUES (1,'p','XTREAM','http://x','u','pw',NULL,'TS','',NULL,'UK|')")
            db.execSQL("INSERT INTO custom_group (id,providerId,name,sortOrder) VALUES (1,1,'Mine',0)")
        }
        helper.runMigrationsAndValidate(DB, 5, true).use { db ->
            db.query("SELECT name, placement FROM custom_group").use { c -> c.moveToFirst(); assertEquals("Mine", c.getString(0)); assertEquals("NORMAL", c.getString(1)) }
            db.query("SELECT nameStrip, categoryStrip FROM provider").use { c -> c.moveToFirst(); assertEquals("UK|", c.getString(0)); assertEquals("", c.getString(1)) }
        }
    }

    @Test fun v3ToV4AddsCountIndexesAndKeepsRows() {
        helper.createDatabase(DB, 3).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync,nameStrip) VALUES (1,'p','XTREAM','http://x','u','pw',NULL,'TS','',NULL,'')")
            db.execSQL("INSERT INTO category (id,providerId,kind,remoteId,name,sortOrder,hidden,locked,pinned,country) VALUES (1,1,'MOVIE','10','Films',0,0,0,0,NULL)")
            db.execSQL("INSERT INTO movie (id,providerId,categoryId,remoteId,name,streamUrl,addedAt) VALUES (1,1,1,'5','Film','http://m',0)")
        }
        helper.runMigrationsAndValidate(DB, 4, true).use { db ->
            db.query("SELECT count(*) FROM movie").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND name IN ('index_movie_providerId_categoryId','index_series_providerId_categoryId')")
                .use { c -> assertEquals(2, c.count) }
        }
    }

    @Test fun v6ToV7AddsRecordingTableAndMaxConnections() {
        helper.createDatabase(DB, 6).use { db ->
            db.execSQL("INSERT INTO provider (id,name,type,url,username,password,userAgent,streamFormat,epgOverrideUrls,lastSync,nameStrip,categoryStrip) VALUES (1,'p','XTREAM','http://x','u','pw',NULL,'TS','',NULL,'','')")
        }
        helper.runMigrationsAndValidate(DB, 7, true).use { db ->
            db.query("SELECT name, maxConnections FROM provider").use { c -> c.moveToFirst(); assertEquals("p", c.getString(0)); assertTrue(c.isNull(1)) }
            db.query("SELECT count(*) FROM recording").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }
}
