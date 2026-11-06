package com.example.perfectbitrate

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// ★ Local Music のプレイリスト永続化（ユーザー作成データ）。
//   ライブラリのキャッシュ DB（LibraryCacheDb）とは別 DB にする:
//   キャッシュ側はスキーマ変更時に作り直す運用だが、プレイリストは
//   ユーザーが作ったデータなので、キャッシュ再構築で消えてはならない。
//   そのため独立した DB ファイル＋移行はテーブル追加のみ、という
//   保守的な運用にする（現行バージョンでは onUpgrade は何もしない）。
//
//   曲の同一性はライブラリ側の Track.uri（文字列）をキーにする。
//   追加時点でタイトル/アーティスト/アルバムのスナップショットも
//   保存し、ライブラリから消えた曲でも名前を表示できるようにする
//   （再生可否の判定は呼び出し側が現在のライブラリと突き合わせる）。
//   プレイリストの件数・曲数とも小さい前提で、全 API は同期実行する
//   （呼び出しは UI スレッドからで問題ない規模）。
class PlaylistStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class Playlist(
        val id: Long,
        val name: String,
        val createdAtMs: Long,
        val itemCount: Int
    )

    data class Item(
        val uri: String,
        val title: String,
        val artist: String,
        val album: String,
        val position: Int
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_PLAYLISTS (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE $TABLE_ITEMS (" +
                "playlist_id INTEGER NOT NULL, " +
                "uri TEXT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "artist TEXT NOT NULL, " +
                "album TEXT NOT NULL, " +
                "position INTEGER NOT NULL, " +
                "PRIMARY KEY (playlist_id, uri))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // ユーザーデータを消す移行はしない。将来のスキーマ変更は
        // テーブル/列の追加のみで対応すること。
    }

    // ★ プレイリスト一覧を作成順（古い順）で返す。曲数も併せて数える。
    fun listPlaylists(): List<Playlist> {
        val result = mutableListOf<Playlist>()
        readableDatabase.rawQuery(
            "SELECT p.id, p.name, p.created_at, COUNT(i.uri) " +
                "FROM $TABLE_PLAYLISTS p " +
                "LEFT JOIN $TABLE_ITEMS i ON i.playlist_id = p.id " +
                "GROUP BY p.id ORDER BY p.created_at ASC, p.id ASC",
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                result += Playlist(
                    id = cursor.getLong(0),
                    name = cursor.getString(1) ?: "",
                    createdAtMs = cursor.getLong(2),
                    itemCount = cursor.getInt(3)
                )
            }
        }
        return result
    }

    fun getPlaylist(id: Long): Playlist? {
        readableDatabase.query(
            TABLE_PLAYLISTS, arrayOf("id", "name", "created_at"),
            "id = ?", arrayOf(id.toString()), null, null, null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val count = countItems(id)
            return Playlist(
                id = cursor.getLong(0),
                name = cursor.getString(1) ?: "",
                createdAtMs = cursor.getLong(2),
                itemCount = count
            )
        }
        return null
    }

    // ★「最後に作成したプレイリスト」（★タップの追加先）。
    //   作成日時が最新のもの。同値なら id の大きい方（後に作られた方）。
    fun latestPlaylist(): Playlist? {
        readableDatabase.query(
            TABLE_PLAYLISTS, arrayOf("id", "name", "created_at"),
            null, null, null, null, "created_at DESC, id DESC", "1"
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getLong(0)
            return Playlist(
                id = id,
                name = cursor.getString(1) ?: "",
                createdAtMs = cursor.getLong(2),
                itemCount = countItems(id)
            )
        }
        return null
    }

    fun createPlaylist(name: String): Long {
        val values = ContentValues().apply {
            put("name", name)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert(TABLE_PLAYLISTS, null, values)
    }

    fun renamePlaylist(id: Long, name: String) {
        val values = ContentValues().apply { put("name", name) }
        writableDatabase.update(TABLE_PLAYLISTS, values, "id = ?", arrayOf(id.toString()))
    }

    fun deletePlaylist(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_ITEMS, "playlist_id = ?", arrayOf(id.toString()))
            db.delete(TABLE_PLAYLISTS, "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ★ プレイリスト内の曲を追加順で返す。
    fun items(playlistId: Long): List<Item> {
        val result = mutableListOf<Item>()
        readableDatabase.query(
            TABLE_ITEMS, arrayOf("uri", "title", "artist", "album", "position"),
            "playlist_id = ?", arrayOf(playlistId.toString()),
            null, null, "position ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                result += Item(
                    uri = cursor.getString(0) ?: "",
                    title = cursor.getString(1) ?: "",
                    artist = cursor.getString(2) ?: "",
                    album = cursor.getString(3) ?: "",
                    position = cursor.getInt(4)
                )
            }
        }
        return result
    }

    fun contains(playlistId: Long, uri: String): Boolean {
        readableDatabase.query(
            TABLE_ITEMS, arrayOf("uri"),
            "playlist_id = ? AND uri = ?", arrayOf(playlistId.toString(), uri),
            null, null, null, "1"
        )?.use { cursor ->
            return cursor.moveToFirst()
        }
        return false
    }

    // ★ 曲を末尾に追加する。既に追加済みなら何もせず false を返す
    //   （主キーが (playlist_id, uri) のため重複行は構造的に生じない）。
    fun addItem(playlistId: Long, track: LibraryRepository.Track): Boolean {
        val uri = track.uri.toString()
        if (contains(playlistId, uri)) return false
        val values = ContentValues().apply {
            put("playlist_id", playlistId)
            put("uri", uri)
            put("title", track.title.ifBlank { track.displayName })
            put("artist", track.artist)
            put("album", track.album)
            put("position", nextPosition(playlistId))
        }
        return writableDatabase.insert(TABLE_ITEMS, null, values) >= 0
    }

    fun removeItem(playlistId: Long, uri: String) {
        writableDatabase.delete(
            TABLE_ITEMS, "playlist_id = ? AND uri = ?",
            arrayOf(playlistId.toString(), uri)
        )
    }

    private fun countItems(playlistId: Long): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_ITEMS WHERE playlist_id = ?",
            arrayOf(playlistId.toString())
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0)
        }
        return 0
    }

    private fun nextPosition(playlistId: Long): Int {
        readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM $TABLE_ITEMS WHERE playlist_id = ?",
            arrayOf(playlistId.toString())
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0)
        }
        return 0
    }

    companion object {
        private const val DB_NAME = "local_playlists.db"
        private const val DB_VERSION = 1
        private const val TABLE_PLAYLISTS = "playlists"
        private const val TABLE_ITEMS = "playlist_items"
    }
}
