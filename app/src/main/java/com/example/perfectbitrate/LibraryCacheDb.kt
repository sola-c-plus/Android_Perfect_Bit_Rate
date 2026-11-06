package com.example.perfectbitrate

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

// ★ Local Music ライブラリの永続化キャッシュ DB（仕様書 §6.2）。
//   FFmpeg プローブ済みのメタデータをアプリ内ストレージに保持し、
//   次回起動時はまず DB の内容を即表示できるようにする。差分判定
//   （URI + サイズ + 更新日時）は LibraryRepository 側の責務で、
//   ここは Track の読み書きだけを担う。
//   依存追加を避けるため Room は使わず、フレームワーク標準の
//   SQLiteOpenHelper で実装する。キャッシュは再スキャンで再構築
//   できるため、スキーマ変更時は作り直しで移行する。
class LibraryCacheDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "uri TEXT PRIMARY KEY, " +
                "display_name TEXT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "artist TEXT NOT NULL, " +
                "album TEXT NOT NULL, " +
                "duration_ms INTEGER NOT NULL, " +
                "size_bytes INTEGER NOT NULL, " +
                "modified_ms INTEGER NOT NULL, " +
                "sample_rate INTEGER NOT NULL, " +
                "bit_depth INTEGER NOT NULL, " +
                "codec_name TEXT NOT NULL, " +
                "is_dsd INTEGER NOT NULL, " +
                "source TEXT NOT NULL, " +
                // 実ファイル同一性キー（v2 で追加。取れない経路では NULL）
                "file_key TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    // ★ 全件読み込み（起動直後の即表示と差分判定の土台）。
    fun loadAll(): List<LibraryRepository.Track> {
        val result = mutableListOf<LibraryRepository.Track>()
        readableDatabase.query(TABLE, COLUMNS, null, null, null, null, null)?.use { cursor ->
            val uriCol = cursor.getColumnIndexOrThrow("uri")
            val nameCol = cursor.getColumnIndexOrThrow("display_name")
            val titleCol = cursor.getColumnIndexOrThrow("title")
            val artistCol = cursor.getColumnIndexOrThrow("artist")
            val albumCol = cursor.getColumnIndexOrThrow("album")
            val durationCol = cursor.getColumnIndexOrThrow("duration_ms")
            val sizeCol = cursor.getColumnIndexOrThrow("size_bytes")
            val modifiedCol = cursor.getColumnIndexOrThrow("modified_ms")
            val rateCol = cursor.getColumnIndexOrThrow("sample_rate")
            val depthCol = cursor.getColumnIndexOrThrow("bit_depth")
            val codecCol = cursor.getColumnIndexOrThrow("codec_name")
            val dsdCol = cursor.getColumnIndexOrThrow("is_dsd")
            val sourceCol = cursor.getColumnIndexOrThrow("source")
            val fileKeyCol = cursor.getColumnIndexOrThrow("file_key")
            while (cursor.moveToNext()) {
                result += LibraryRepository.Track(
                    uri = Uri.parse(cursor.getString(uriCol)),
                    displayName = cursor.getString(nameCol) ?: "",
                    title = cursor.getString(titleCol) ?: "",
                    artist = cursor.getString(artistCol) ?: "",
                    album = cursor.getString(albumCol) ?: "",
                    durationMs = cursor.getLong(durationCol),
                    sizeBytes = cursor.getLong(sizeCol),
                    modifiedMs = cursor.getLong(modifiedCol),
                    sampleRate = cursor.getInt(rateCol),
                    bitDepth = cursor.getInt(depthCol),
                    codecName = cursor.getString(codecCol) ?: "",
                    isDsd = cursor.getInt(dsdCol) != 0,
                    source = try {
                        LibraryRepository.Track.Source.valueOf(cursor.getString(sourceCol) ?: "")
                    } catch (e: Exception) {
                        LibraryRepository.Track.Source.MEDIASTORE
                    },
                    fileKey = cursor.getString(fileKeyCol)?.takeIf { it.isNotEmpty() }
                )
            }
        }
        return result
    }

    // ★ 新規/変更分の upsert（INSERT OR REPLACE）と削除分を
    //   1 トランザクションでまとめて適用する。
    fun applyChanges(
        upserts: List<LibraryRepository.Track>,
        deleteUris: Collection<String>
    ) {
        if (upserts.isEmpty() && deleteUris.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (track in upserts) {
                db.replace(TABLE, null, toValues(track))
            }
            // SQLite のバインド変数上限に当たらないよう分割して消す
            for (chunk in deleteUris.chunked(DELETE_CHUNK)) {
                val placeholders = chunk.joinToString(",") { "?" }
                db.delete(TABLE, "uri IN ($placeholders)", chunk.toTypedArray())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun toValues(track: LibraryRepository.Track): ContentValues {
        return ContentValues().apply {
            put("uri", track.uri.toString())
            put("display_name", track.displayName)
            put("title", track.title)
            put("artist", track.artist)
            put("album", track.album)
            put("duration_ms", track.durationMs)
            put("size_bytes", track.sizeBytes)
            put("modified_ms", track.modifiedMs)
            put("sample_rate", track.sampleRate)
            put("bit_depth", track.bitDepth)
            put("codec_name", track.codecName)
            put("is_dsd", if (track.isDsd) 1 else 0)
            put("source", track.source.name)
            put("file_key", track.fileKey)
        }
    }

    companion object {
        private const val DB_NAME = "local_library.db"
        // v2: file_key 列を追加（onUpgrade は作り直しなので移行は不要）
        private const val DB_VERSION = 2
        private const val TABLE = "tracks"
        private const val DELETE_CHUNK = 400
        private val COLUMNS = arrayOf(
            "uri", "display_name", "title", "artist", "album",
            "duration_ms", "size_bytes", "modified_ms",
            "sample_rate", "bit_depth", "codec_name", "is_dsd", "source",
            "file_key"
        )
    }
}
