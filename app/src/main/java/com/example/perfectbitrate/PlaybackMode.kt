package com.example.perfectbitrate

// ★ 再生ソース（モード）の定義（仕様書 §3.1）。
//   永続化は AppPreferences.playbackMode が担い、保存値には prefValue を使う。
//   将来ソースが増えたら（Spotify 等）ここに列挙子を足し、供給係は
//   SourceListener 共通契約（仕様書 §4.1）で受ける前提とする。
enum class PlaybackMode(val prefValue: String, val displayName: String) {
    YT_MUSIC("yt_music", "YouTube Music"),
    LOCAL("local", "Local Music");

    companion object {
        fun fromPrefValue(value: String?): PlaybackMode =
            entries.firstOrNull { it.prefValue == value } ?: YT_MUSIC
    }
}
