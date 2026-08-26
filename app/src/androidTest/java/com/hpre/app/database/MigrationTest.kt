package com.hpre.app.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HPreDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun create_version_1_schema_and_validate_tables_and_indices() {
        val db = helper.createDatabase(TEST_DB, 1)

        // Validate table existence
        val tables = mutableListOf<String>()
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table'")
        while (cursor.moveToNext()) {
            tables.add(cursor.getString(0))
        }
        cursor.close()

        assertTrue("watch_history table must exist", tables.contains("watch_history"))
        assertTrue("local_subscriptions table must exist", tables.contains("local_subscriptions"))
        assertTrue("local_playlists table must exist", tables.contains("local_playlists"))
        assertTrue("local_playlist_entries table must exist", tables.contains("local_playlist_entries"))
        assertTrue("search_history table must exist", tables.contains("search_history"))

        // Validate watch_history schema does NOT have any stream URL column
        val watchHistoryCols = mutableListOf<String>()
        val watchCursor = db.query("PRAGMA table_info(watch_history)")
        while (watchCursor.moveToNext()) {
            watchHistoryCols.add(watchCursor.getString(1))
        }
        watchCursor.close()

        assertTrue(watchHistoryCols.contains("serviceId"))
        assertTrue(watchHistoryCols.contains("videoId"))
        assertTrue(watchHistoryCols.contains("canonicalUrl"))
        assertTrue(watchHistoryCols.contains("title"))
        assertTrue(watchHistoryCols.contains("playbackPositionMs"))
        assertTrue(watchHistoryCols.contains("watchedTimestamp"))
        assertTrue("No stream URL columns in watch_history", watchHistoryCols.none { it.contains("stream", ignoreCase = true) })

        // Validate local_playlist_entries schema
        val playlistEntriesCols = mutableListOf<String>()
        val peCursor = db.query("PRAGMA table_info(local_playlist_entries)")
        while (peCursor.moveToNext()) {
            playlistEntriesCols.add(peCursor.getString(1))
        }
        peCursor.close()

        assertTrue(playlistEntriesCols.contains("playlistId"))
        assertTrue(playlistEntriesCols.contains("serviceId"))
        assertTrue(playlistEntriesCols.contains("videoId"))
        assertTrue(playlistEntriesCols.contains("sortOrder"))
        assertTrue("No stream URL columns in local_playlist_entries", playlistEntriesCols.none { it.contains("stream", ignoreCase = true) })

        // Helper lambda to get indices for a table
        fun getIndices(tableName: String): List<String> {
            val indices = mutableListOf<String>()
            val idxCursor = db.query("PRAGMA index_list($tableName)")
            while (idxCursor.moveToNext()) {
                indices.add(idxCursor.getString(1)) // name column
            }
            idxCursor.close()
            return indices
        }

        // Helper lambda to get indexed columns for a specific index name
        fun getIndexColumns(indexName: String): List<String> {
            val cols = mutableListOf<String>()
            val infoCursor = db.query("PRAGMA index_info($indexName)")
            while (infoCursor.moveToNext()) {
                cols.add(infoCursor.getString(2)) // column name
            }
            infoCursor.close()
            return cols
        }

        // Helper lambda to get foreign key details for a table: List of Triple(table, fromColumn, toColumn, onDelete)
        fun getForeignKeys(tableName: String): List<List<String>> {
            val fks = mutableListOf<List<String>>()
            val fkCursor = db.query("PRAGMA foreign_key_list($tableName)")
            while (fkCursor.moveToNext()) {
                val table = fkCursor.getString(2)
                val from = fkCursor.getString(3)
                val to = fkCursor.getString(4)
                val onUpdate = fkCursor.getString(5)
                val onDelete = fkCursor.getString(6)
                fks.add(listOf(table, from, to, onUpdate, onDelete))
            }
            fkCursor.close()
            return fks
        }

        // Validate watch_history indices
        val watchIndices = getIndices("watch_history")
        assertTrue("watch_history must contain index_watch_history_watchedTimestamp", watchIndices.contains("index_watch_history_watchedTimestamp"))
        assertEquals(listOf("watchedTimestamp"), getIndexColumns("index_watch_history_watchedTimestamp"))

        // Validate local_subscriptions indices
        val subIndices = getIndices("local_subscriptions")
        assertTrue("local_subscriptions must contain index_local_subscriptions_subscribedTimestamp", subIndices.contains("index_local_subscriptions_subscribedTimestamp"))
        assertEquals(listOf("subscribedTimestamp"), getIndexColumns("index_local_subscriptions_subscribedTimestamp"))

        // Validate local_playlists indices
        val playlistIndices = getIndices("local_playlists")
        assertTrue("local_playlists must contain index_local_playlists_updatedTimestamp", playlistIndices.contains("index_local_playlists_updatedTimestamp"))
        assertEquals(listOf("updatedTimestamp"), getIndexColumns("index_local_playlists_updatedTimestamp"))

        // Validate search_history indices
        val searchIndices = getIndices("search_history")
        assertTrue("search_history must contain index_search_history_searchedTimestamp", searchIndices.contains("index_search_history_searchedTimestamp"))
        assertEquals(listOf("searchedTimestamp"), getIndexColumns("index_search_history_searchedTimestamp"))

        // Validate local_playlist_entries indices
        val peIndices = getIndices("local_playlist_entries")
        assertTrue("local_playlist_entries must contain index_local_playlist_entries_playlistId_sortOrder", peIndices.contains("index_local_playlist_entries_playlistId_sortOrder"))
        assertEquals(listOf("playlistId", "sortOrder"), getIndexColumns("index_local_playlist_entries_playlistId_sortOrder"))
        assertTrue("local_playlist_entries must contain index_local_playlist_entries_playlistId", peIndices.contains("index_local_playlist_entries_playlistId"))
        assertEquals(listOf("playlistId"), getIndexColumns("index_local_playlist_entries_playlistId"))

        // Validate foreign key on local_playlist_entries referencing local_playlists(playlistId) ON DELETE CASCADE
        val peFks = getForeignKeys("local_playlist_entries")
        assertEquals(1, peFks.size)
        val peFk = peFks.first()
        assertEquals("local_playlists", peFk[0]) // referenced table
        assertEquals("playlistId", peFk[1]) // from column
        assertEquals("playlistId", peFk[2]) // to column
        assertEquals("CASCADE", peFk[4]) // on delete action

        db.close()
    }
}
