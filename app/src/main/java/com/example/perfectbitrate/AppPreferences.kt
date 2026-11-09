package com.example.perfectbitrate

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class CustomEqPreset(
    val id: String,
    var name: String,
    val gains: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CustomEqPreset
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

// ★ Local キューの位置保存のスナップショット（LocalPlayer が作り、
//   起動時の復元で読み戻す）。キュー本体は URI 文字列の並び、実効
//   再生順はキュー内インデックスの並び（order[実効位置] = キュー内
//   インデックス、LocalPlayer の playOrder と同じ表現）で持つ。
//   orderPosition は実効順上の現在位置、positionMs は現在曲の
//   再生位置（表示位置 = 実際に聞こえている位置）。
data class LocalQueueState(
    val queueUris: List<String>,
    val order: List<Int>,
    val orderPosition: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: Int
)

class AppPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "bp_settings"

        private const val KEY_DIRECT_SOURCE = "direct_source_enabled"
        private const val KEY_CASCADE_FIR = "cascade_fir_enabled"
        private const val KEY_LR_DITHER = "lr_dither_enabled"
        private const val KEY_SPECTRUM_ENABLED = "spectrum_enabled"
        private const val KEY_AD_BLOCK_ENABLED = "ad_block_enabled"
        private const val KEY_UI_THEME_MODE = "ui_theme_mode"
        private const val KEY_PLAYBACK_MODE = "playbackMode"
        private const val KEY_USE_MEDIASTORE = "useMediaStore"
        private const val KEY_SAF_FOLDER_URIS = "safFolderUris"
        private const val KEY_DSD_PCM_RATE_HZ = "dsdPcmRateHz"
        private const val KEY_LOCAL_RESCAN_REQUESTED = "localRescanRequested"
        private const val KEY_LOCAL_SORT_MODE = "localSortMode"
        private const val KEY_LOCAL_SHUFFLE_ENABLED = "localShuffleEnabled"
        private const val KEY_LOCAL_REPEAT_MODE = "localRepeatMode"
        private const val KEY_LOCAL_ARTIST_GROUPING = "localArtistGrouping"
        private const val KEY_LOCAL_NP_VIEW_MODE = "localNpViewMode"
        private const val KEY_SLEEP_TIMER_MINUTES = "sleepTimerMinutes"
        private const val KEY_SLEEP_TIMER_END_AT_MS = "sleepTimerEndAtMs"
        private const val KEY_LOCAL_QUEUE_STATE_JSON = "localQueueStateJson"

        // ★ スリープタイマーの選択肢（分）。0 は OFF。期限は
        //   sleepTimerEndAtMs（UNIX ミリ秒、0 = 未設定）を正とし、
        //   MainActivity の UI ループが期限到来でポーズさせる。
        const val SLEEP_TIMER_OFF = 0
        val SLEEP_TIMER_CHOICES_MINUTES = intArrayOf(15, 30, 60)

        // ★ Local ライブラリ「全曲」タブのソート種別（Phase 2-1）。
        const val LOCAL_SORT_TITLE = 0
        const val LOCAL_SORT_ARTIST = 1
        const val LOCAL_SORT_ALBUM = 2

        // ★ Local 再生のリピート種別（Now Playing）。トグルは
        //   OFF → 全曲 → 1曲 の順に循環する。
        const val LOCAL_REPEAT_OFF = 0
        const val LOCAL_REPEAT_ALL = 1
        const val LOCAL_REPEAT_ONE = 2

        // ★ Local ライブラリ「アーティスト」タブのグルーピングキー。
        //   ALBUM_ARTIST: アルバムアーティスト（タグの album_artist、
        //   無い曲はアーティストで代替）。ARTIST: 曲のアーティスト。
        const val LOCAL_ARTIST_GROUPING_ALBUM_ARTIST = 0
        const val LOCAL_ARTIST_GROUPING_ARTIST = 1

        // ★ Local 再生画面（Now Playing）のアートワーク枠に表示する
        //   ビューの種別。タップでこの順に循環する。WAVEFORM は
        //   PR-2 で追加予定の予約値で、PR-1 の循環範囲
        //   （LOCAL_NP_VIEW_LAST）にはまだ含めない。
        const val LOCAL_NP_VIEW_ARTWORK = 0
        const val LOCAL_NP_VIEW_SPECTRUM = 1
        const val LOCAL_NP_VIEW_VU = 2
        const val LOCAL_NP_VIEW_DETAILS = 3
        const val LOCAL_NP_VIEW_WAVEFORM = 4
        const val LOCAL_NP_VIEW_LAST = LOCAL_NP_VIEW_DETAILS
        private const val KEY_VOL_LOCK_ENABLED = "vol_lock_enabled"
        private const val KEY_SELECTED_BIT_MODE = "selected_bit_mode"
        private const val KEY_SELECTED_PERF_MODE = "selected_perf_mode"
        private const val KEY_SELECTED_UPSAMPLE_FACTOR = "selected_upsample_factor"
        private const val KEY_SELECTED_DITHER_MODE = "selected_dither_mode"
        private const val KEY_SELECTED_DC_PHASE_TYPE = "selected_dc_phase_type"
        private const val KEY_SELECTED_PRESET_INDEX = "selected_preset_index"
        private const val KEY_RICH_HARMONICS_ENABLED = "rich_harmonics_enabled"
        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_SYS_MONITOR_ENABLED = "sys_monitor_enabled"
        private const val KEY_HAPTIC_FEEDBACK_ENABLED = "haptic_feedback_enabled"
        private const val KEY_TRUE_PEAK_GUARD_ENABLED = "true_peak_guard_enabled"
        private const val KEY_TRUE_PEAK_CEILING = "true_peak_ceiling"
        private const val KEY_SELECTED_EQ_ID = "selected_eq_id"
        private const val KEY_CURRENT_EQ_GAIN_PREFIX = "current_eq_gain_"
        private const val KEY_WORKING_CUSTOM_GAIN_PREFIX = "working_custom_gain_"
        private const val KEY_CUSTOM_EQ_PRESETS_JSON = "custom_eq_presets_json"
        private const val KEY_FREQ_PRESETS_JSON = "freq_presets_json"

        // True Peak ガードの天井選択肢 (インデックス → dBTP)
        val TRUE_PEAK_CEILING_DB_VALUES = floatArrayOf(0.0f, -0.5f, -1.0f, -2.0f)

        // DSD → PCM 変換レートの選択肢 (Hz)。デフォルトは 176.4kHz（仕様書 §5.2）
        val DSD_PCM_RATE_CHOICES = intArrayOf(88200, 176400, 352800)
        const val DSD_PCM_RATE_DEFAULT = 176400

        @Volatile
        private var instance: AppPreferences? = null

        fun init(context: Context): AppPreferences {
            return instance ?: synchronized(this) {
                instance ?: AppPreferences(context.applicationContext).also { instance = it }
            }
        }

        fun get(): AppPreferences {
            return instance ?: throw IllegalStateException("AppPreferences is not initialized. Call init(context) first.")
        }
    }

    var isDirectSource: Boolean
        get() = prefs.getBoolean(KEY_DIRECT_SOURCE, false)
        set(value) = prefs.edit { putBoolean(KEY_DIRECT_SOURCE, value) }

    var isCascadeFir: Boolean
        get() = prefs.getBoolean(KEY_CASCADE_FIR, true)
        set(value) = prefs.edit { putBoolean(KEY_CASCADE_FIR, value) }

    var isLrIndependentDither: Boolean
        get() = prefs.getBoolean(KEY_LR_DITHER, true)
        set(value) = prefs.edit { putBoolean(KEY_LR_DITHER, value) }

    var isSpectrumEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPECTRUM_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_SPECTRUM_ENABLED, value) }

    var isAdBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_AD_BLOCK_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_AD_BLOCK_ENABLED, value) }

    var isSysMonitorEnabled: Boolean
        get() = prefs.getBoolean(KEY_SYS_MONITOR_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_SYS_MONITOR_ENABLED, value) }

    var isHapticFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_FEEDBACK_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_HAPTIC_FEEDBACK_ENABLED, value) }

    // True Peak ガード: デフォルト ON / 天井 -1.0dBTP (インデックス 2)。
    // 天井インデックスと dBTP の対応は TRUE_PEAK_CEILING_DB_VALUES が正。
    var isTruePeakGuardEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRUE_PEAK_GUARD_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_TRUE_PEAK_GUARD_ENABLED, value) }

    var selectedTruePeakCeiling: Int
        get() = prefs.getInt(KEY_TRUE_PEAK_CEILING, 2)
        set(value) = prefs.edit { putInt(KEY_TRUE_PEAK_CEILING, value) }

    fun truePeakCeilingDb(): Float {
        val idx = selectedTruePeakCeiling.coerceIn(0, TRUE_PEAK_CEILING_DB_VALUES.lastIndex)
        return TRUE_PEAK_CEILING_DB_VALUES[idx]
    }

    var uiThemeMode: String
        get() = prefs.getString(KEY_UI_THEME_MODE, "dark") ?: "dark"
        set(value) = prefs.edit { putString(KEY_UI_THEME_MODE, value) }

    // ★ 再生モード（仕様書 §3.1）: 起動時は前回値を復元する。Vol Lock のような
    //   安全側リセットはしない（モードは音量に直結しないため）。
    var playbackMode: PlaybackMode
        get() = PlaybackMode.fromPrefValue(prefs.getString(KEY_PLAYBACK_MODE, PlaybackMode.YT_MUSIC.prefValue))
        set(value) = prefs.edit { putString(KEY_PLAYBACK_MODE, value.prefValue) }

    // ★ Local Music のライブラリ取得範囲（仕様書 §6.1）。
    //   設定画面（SettingsActivity）から変更する。
    var useMediaStore: Boolean
        get() = prefs.getBoolean(KEY_USE_MEDIASTORE, true)
        set(value) = prefs.edit { putBoolean(KEY_USE_MEDIASTORE, value) }

    // SAF で追加したフォルダ（ツリー URI 文字列）の集合。永続化許可
    // (takePersistableUriPermission) を取った URI のみを入れる前提。
    var safFolderUris: Set<String>
        get() = prefs.getStringSet(KEY_SAF_FOLDER_URIS, emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_SAF_FOLDER_URIS, value) }

    // ★ DSD → PCM 変換レート (Hz、仕様書 §5.2)。選択肢外の値はデフォルトへ倒す。
    var dsdPcmRateHz: Int
        get() {
            val stored = prefs.getInt(KEY_DSD_PCM_RATE_HZ, DSD_PCM_RATE_DEFAULT)
            return if (stored in DSD_PCM_RATE_CHOICES) stored else DSD_PCM_RATE_DEFAULT
        }
        set(value) = prefs.edit { putInt(KEY_DSD_PCM_RATE_HZ, value) }

    // ★ ライブラリ再スキャンの予約フラグ（仕様書 §6.1 の手動再スキャン）。
    //   設定画面の「ライブラリの再スキャン」で立て、MainActivity が次に
    //   Local のスキャンを開始するときに強制フルリフレッシュとして
    //   消費する（消費したら下ろす）。
    var localRescanRequested: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_RESCAN_REQUESTED, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCAL_RESCAN_REQUESTED, value) }

    // ★ Local ライブラリ「全曲」タブのソート種別（Phase 2-1）。
    //   選択肢外の値はタイトル順へ倒す（DSD 変換レートと同じ流儀）。
    var localSortMode: Int
        get() {
            val stored = prefs.getInt(KEY_LOCAL_SORT_MODE, LOCAL_SORT_TITLE)
            return if (stored in LOCAL_SORT_TITLE..LOCAL_SORT_ALBUM) stored else LOCAL_SORT_TITLE
        }
        set(value) = prefs.edit { putInt(KEY_LOCAL_SORT_MODE, value) }

    // ★ Local 再生画面のアートワーク枠の表示ビュー（Now Playing）。
    //   範囲外の値はアートワーク表示へ倒す（他の選択系と同じ流儀）。
    var localNpViewMode: Int
        get() {
            val stored = prefs.getInt(KEY_LOCAL_NP_VIEW_MODE, LOCAL_NP_VIEW_ARTWORK)
            return if (stored in LOCAL_NP_VIEW_ARTWORK..LOCAL_NP_VIEW_LAST) {
                stored
            } else {
                LOCAL_NP_VIEW_ARTWORK
            }
        }
        set(value) = prefs.edit { putInt(KEY_LOCAL_NP_VIEW_MODE, value) }

    // ★ Local 再生のシャッフル ON/OFF（Now Playing）。状態の正はここで、
    //   LocalPlayer の自動進行が都度読む。
    var localShuffleEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_SHUFFLE_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCAL_SHUFFLE_ENABLED, value) }

    // ★ Local 再生のリピート種別（Now Playing）。選択肢外の値は OFF へ倒す
    //   （ソート種別と同じ流儀）。
    var localRepeatMode: Int
        get() {
            val stored = prefs.getInt(KEY_LOCAL_REPEAT_MODE, LOCAL_REPEAT_OFF)
            return if (stored in LOCAL_REPEAT_OFF..LOCAL_REPEAT_ONE) stored else LOCAL_REPEAT_OFF
        }
        set(value) = prefs.edit { putInt(KEY_LOCAL_REPEAT_MODE, value) }

    // ★ Local ライブラリ「アーティスト」タブのグルーピングキー
    //   （アルバムアーティスト / アーティスト）。選択肢外の値は
    //   アルバムアーティストへ倒す。導出（MainActivity 側）が都度
    //   読むため、変更は次回のアーティスト表示へ即時反映される。
    var localArtistGrouping: Int
        get() {
            val stored = prefs.getInt(
                KEY_LOCAL_ARTIST_GROUPING, LOCAL_ARTIST_GROUPING_ALBUM_ARTIST
            )
            return if (stored in
                LOCAL_ARTIST_GROUPING_ALBUM_ARTIST..LOCAL_ARTIST_GROUPING_ARTIST
            ) {
                stored
            } else {
                LOCAL_ARTIST_GROUPING_ALBUM_ARTIST
            }
        }
        set(value) = prefs.edit { putInt(KEY_LOCAL_ARTIST_GROUPING, value) }

    // ★ スリープタイマーの選択（分、0 = OFF）。選択肢外の値は OFF へ
    //   倒す（他の選択系と同じ流儀）。発火期限は sleepTimerEndAtMs が
    //   正で、こちらは設定画面での表示（何分を選んだか）に使う。
    var sleepTimerMinutes: Int
        get() {
            val stored = prefs.getInt(KEY_SLEEP_TIMER_MINUTES, SLEEP_TIMER_OFF)
            return if (stored == SLEEP_TIMER_OFF || stored in SLEEP_TIMER_CHOICES_MINUTES) {
                stored
            } else {
                SLEEP_TIMER_OFF
            }
        }
        set(value) = prefs.edit { putInt(KEY_SLEEP_TIMER_MINUTES, value) }

    // ★ スリープタイマーの発火期限（UNIX ミリ秒、0 = 未設定）。
    //   設定時に「現在時刻＋選択分」で書き、期限到来で発火した側
    //   （MainActivity）が 0 へ戻す。期限を過ぎた値が残っていたら
    //   未設定として扱う（読み手側の判定に使う）。
    var sleepTimerEndAtMs: Long
        get() = prefs.getLong(KEY_SLEEP_TIMER_END_AT_MS, 0L)
        set(value) = prefs.edit { putLong(KEY_SLEEP_TIMER_END_AT_MS, value) }

    // ★ Local キューの位置保存（常時有効、ON/OFF 設定なし）。
    //   保存先は SharedPreferences の JSON 1 件（カスタム EQ /
    //   Freq プリセットと同じ流儀）。キューは URI 文字列の配列、
    //   実効順はインデックス配列で持つため、数千曲規模でも JSON は
    //   URI 合計＋数バイト/曲程度に収まり、DB のスキーマ変更なしで
    //   既存の永続化パターンに乗せられる。読み出しは防御的に行い、
    //   壊れた JSON や範囲外の値は「保存なし」へ倒す。
    fun saveLocalQueueState(state: LocalQueueState) {
        val obj = JSONObject()
        val uris = JSONArray()
        for (uri in state.queueUris) uris.put(uri)
        obj.put("queueUris", uris)
        val order = JSONArray()
        for (index in state.order) order.put(index)
        obj.put("order", order)
        obj.put("orderPosition", state.orderPosition)
        obj.put("positionMs", state.positionMs)
        obj.put("shuffle", state.shuffleEnabled)
        obj.put("repeat", state.repeatMode)
        prefs.edit { putString(KEY_LOCAL_QUEUE_STATE_JSON, obj.toString()) }
    }

    fun loadLocalQueueState(): LocalQueueState? {
        val jsonStr = prefs.getString(KEY_LOCAL_QUEUE_STATE_JSON, null)
        if (jsonStr.isNullOrEmpty()) return null
        return try {
            val obj = JSONObject(jsonStr)
            val urisArr = obj.getJSONArray("queueUris")
            val queueUris = ArrayList<String>(urisArr.length())
            for (i in 0 until urisArr.length()) {
                queueUris.add(urisArr.getString(i))
            }
            if (queueUris.isEmpty()) return null
            // 順序表はキュー範囲内の値だけを重複なく拾い、欠けた
            // インデックスは末尾へ元の順で補う（保存形式の将来
            // 変更や部分的な破損でも順序が崩壊しないように）。
            val orderArr = obj.getJSONArray("order")
            val order = ArrayList<Int>(queueUris.size)
            val seen = HashSet<Int>()
            for (i in 0 until orderArr.length()) {
                val index = orderArr.getInt(i)
                if (index in queueUris.indices && seen.add(index)) order.add(index)
            }
            if (order.size < queueUris.size) {
                for (index in queueUris.indices) {
                    if (seen.add(index)) order.add(index)
                }
            }
            val orderPosition = obj.optInt("orderPosition", 0)
                .coerceIn(0, order.lastIndex)
            val repeat = obj.optInt("repeat", LOCAL_REPEAT_OFF)
            LocalQueueState(
                queueUris = queueUris,
                order = order,
                orderPosition = orderPosition,
                positionMs = obj.optLong("positionMs", 0L).coerceAtLeast(0L),
                shuffleEnabled = obj.optBoolean("shuffle", false),
                repeatMode = if (repeat in LOCAL_REPEAT_OFF..LOCAL_REPEAT_ONE) {
                    repeat
                } else {
                    LOCAL_REPEAT_OFF
                }
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clearLocalQueueState() {
        prefs.edit { remove(KEY_LOCAL_QUEUE_STATE_JSON) }
    }

    var isVolLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOL_LOCK_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_VOL_LOCK_ENABLED, value) }

    var selectedBitMode: String
        get() = prefs.getString(KEY_SELECTED_BIT_MODE, "16bit") ?: "16bit"
        set(value) = prefs.edit { putString(KEY_SELECTED_BIT_MODE, value) }

    var selectedPerfMode: Int
        get() = prefs.getInt(KEY_SELECTED_PERF_MODE, 1)
        set(value) = prefs.edit { putInt(KEY_SELECTED_PERF_MODE, value) }

    var selectedUpsampleFactor: Int
        get() = prefs.getInt(KEY_SELECTED_UPSAMPLE_FACTOR, 1)
        set(value) = prefs.edit { putInt(KEY_SELECTED_UPSAMPLE_FACTOR, value) }

    var selectedDitherMode: Int
        get() = prefs.getInt(KEY_SELECTED_DITHER_MODE, 1)
        set(value) = prefs.edit { putInt(KEY_SELECTED_DITHER_MODE, value) }

    var selectedDcPhaseType: Int
        get() = prefs.getInt(KEY_SELECTED_DC_PHASE_TYPE, 2)
        set(value) = prefs.edit { putInt(KEY_SELECTED_DC_PHASE_TYPE, value) }

    var isRichHarmonicsEnabled: Boolean
        get() = prefs.getBoolean(KEY_RICH_HARMONICS_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_RICH_HARMONICS_ENABLED, value) }

    var selectedPresetIndex: Int
        get() = prefs.getInt(KEY_SELECTED_PRESET_INDEX, 1)
        set(value) = prefs.edit { putInt(KEY_SELECTED_PRESET_INDEX, value) }

    var isEqEnabled: Boolean
        get() = prefs.getBoolean(KEY_EQ_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_EQ_ENABLED, value) }

    var selectedEqPresetId: String
        get() = prefs.getString(KEY_SELECTED_EQ_ID, "builtin:Flat") ?: "builtin:Flat"
        set(value) = prefs.edit { putString(KEY_SELECTED_EQ_ID, value) }

    fun getAllEqGains(): FloatArray {
        return FloatArray(10) { i -> prefs.getFloat("$KEY_CURRENT_EQ_GAIN_PREFIX$i", 0.0f) }
    }

    fun setAllEqGains(gains: FloatArray) {
        prefs.edit {
            for (i in 0 until minOf(gains.size, 10)) {
                putFloat("$KEY_CURRENT_EQ_GAIN_PREFIX$i", gains[i])
            }
        }
    }

    fun getWorkingCustomGains(): FloatArray {
        return FloatArray(10) { i -> prefs.getFloat("$KEY_WORKING_CUSTOM_GAIN_PREFIX$i", 0.0f) }
    }

    fun setWorkingCustomGains(gains: FloatArray) {
        prefs.edit {
            for (i in 0 until minOf(gains.size, 10)) {
                putFloat("$KEY_WORKING_CUSTOM_GAIN_PREFIX$i", gains[i])
            }
        }
    }

    fun getSavedCustomEqPresets(): MutableList<CustomEqPreset> {
        val jsonStr = prefs.getString(KEY_CUSTOM_EQ_PRESETS_JSON, null)
        if (jsonStr.isNullOrEmpty()) {
            return mutableListOf()
        }
        val list = mutableListOf<CustomEqPreset>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val gainsArr = obj.getJSONArray("gains")
                val gains = FloatArray(10) { idx ->
                    if (idx < gainsArr.length()) gainsArr.getDouble(idx).toFloat() else 0.0f
                }
                list.add(CustomEqPreset(id, name, gains))
            }
        } catch (e: Exception) {
            list.clear()
        }
        return list
    }

    // ★ Freqプリセット全件の永続化（カスタムEQプリセットと同じ JSONArray/JSONObject 方式）。
    //    古い JSON に欠けているフィールドはデフォルトプリセットの値で補完する。
    fun getSavedFreqPresets(): List<FreqPresetDef>? {
        val jsonStr = prefs.getString(KEY_FREQ_PRESETS_JSON, null)
        if (jsonStr.isNullOrEmpty()) return null
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<FreqPresetDef>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val fallback = FreqPresetManager.DEFAULT_PRESETS.getOrNull(i)
                list.add(
                    FreqPresetDef(
                        id = obj.optInt("id", fallback?.id ?: i),
                        name = obj.optString("name", fallback?.name ?: ""),
                        firType = obj.optInt("firType", fallback?.firType ?: 0),
                        transientMode = obj.optInt("transientMode", fallback?.transientMode ?: 0),
                        lpcAlgo = obj.optInt("lpcAlgo", fallback?.lpcAlgo ?: 0),
                        gain = obj.optDouble("gain", (fallback?.gain ?: 0.0f).toDouble()).toFloat(),
                        extractFreq = obj.optDouble("extractFreq", (fallback?.extractFreq ?: 13000.0f).toDouble()).toFloat(),
                        useQmf = obj.optBoolean("useQmf", fallback?.useQmf ?: false),
                        useGroupDelay = obj.optBoolean("useGroupDelay", fallback?.useGroupDelay ?: false),
                        useLattice = obj.optBoolean("useLattice", fallback?.useLattice ?: false),
                        useLrDither = obj.optBoolean("useLrDither", fallback?.useLrDither ?: false),
                        useMsSpatial = obj.optBoolean("useMsSpatial", fallback?.useMsSpatial ?: false),
                        useDynamicSbr = obj.optBoolean("useDynamicSbr", fallback?.useDynamicSbr ?: false),
                        // 旧 JSON にキーが無い場合はデフォルトプリセット値、無ければ extractFreq × 1.3 で補完
                        qmfCrossoverHz = obj.optDouble(
                            "qmfCrossoverHz",
                            (fallback?.qmfCrossoverHz ?: ((fallback?.extractFreq ?: 13000.0f) * 1.3f)).toDouble()
                        ).toFloat()
                    )
                )
            }
            if (list.size == FreqPresetManager.DEFAULT_PRESETS.size) list else null
        } catch (e: Exception) {
            null
        }
    }

    fun saveFreqPresets(list: List<FreqPresetDef>) {
        val arr = JSONArray()
        for (p in list) {
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            obj.put("firType", p.firType)
            obj.put("transientMode", p.transientMode)
            obj.put("lpcAlgo", p.lpcAlgo)
            obj.put("gain", p.gain.toDouble())
            obj.put("extractFreq", p.extractFreq.toDouble())
            obj.put("useQmf", p.useQmf)
            obj.put("useGroupDelay", p.useGroupDelay)
            obj.put("useLattice", p.useLattice)
            obj.put("useLrDither", p.useLrDither)
            obj.put("useMsSpatial", p.useMsSpatial)
            obj.put("useDynamicSbr", p.useDynamicSbr)
            obj.put("qmfCrossoverHz", p.qmfCrossoverHz.toDouble())
            arr.put(obj)
        }
        prefs.edit { putString(KEY_FREQ_PRESETS_JSON, arr.toString()) }
    }

    fun saveCustomEqPresets(list: List<CustomEqPreset>) {
        val arr = JSONArray()
        for (p in list) {
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            val gainsArr = JSONArray()
            for (g in p.gains) {
                gainsArr.put(g.toDouble())
            }
            obj.put("gains", gainsArr)
            arr.put(obj)
        }
        prefs.edit { putString(KEY_CUSTOM_EQ_PRESETS_JSON, arr.toString()) }
    }
}