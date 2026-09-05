package com.example.perfectbitrate

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

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
        private const val KEY_VOL_LOCK_ENABLED = "vol_lock_enabled"
        private const val KEY_SELECTED_BIT_MODE = "selected_bit_mode"
        private const val KEY_SELECTED_PERF_MODE = "selected_perf_mode"
        private const val KEY_SELECTED_UPSAMPLE_FACTOR = "selected_upsample_factor"
        private const val KEY_SELECTED_DITHER_MODE = "selected_dither_mode"
        private const val KEY_SELECTED_DC_PHASE_TYPE = "selected_dc_phase_type"
        private const val KEY_SELECTED_PRESET_INDEX = "selected_preset_index"
        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_EQ_GAIN_PREFIX = "eq_gain_"

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

    var uiThemeMode: String
        get() = prefs.getString(KEY_UI_THEME_MODE, "dark") ?: "dark"
        set(value) = prefs.edit { putString(KEY_UI_THEME_MODE, value) }

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

    var selectedPresetIndex: Int
        get() = prefs.getInt(KEY_SELECTED_PRESET_INDEX, 1)
        set(value) = prefs.edit { putInt(KEY_SELECTED_PRESET_INDEX, value) }

    var isEqEnabled: Boolean
        get() = prefs.getBoolean(KEY_EQ_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_EQ_ENABLED, value) }

    fun getEqGain(bandIndex: Int): Float {
        return prefs.getFloat("$KEY_EQ_GAIN_PREFIX$bandIndex", 0.0f)
    }

    fun setEqGain(bandIndex: Int, gain: Float) {
        prefs.edit { putFloat("$KEY_EQ_GAIN_PREFIX$bandIndex", gain) }
    }

    fun setAllEqGains(gains: FloatArray) {
        prefs.edit {
            for (i in 0 until minOf(gains.size, 10)) {
                putFloat("$KEY_EQ_GAIN_PREFIX$i", gains[i])
            }
        }
    }
}