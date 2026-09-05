package com.example.perfectbitrate

import android.graphics.Bitmap

interface PlayerDialogController {
    fun updatePlayerState(
        title: String,
        artist: String,
        artwork: Bitmap?,
        isPlaying: Boolean,
        currentPositionMs: Long,
        durationMs: Long
    )
    fun setSpectrumLevels(spectrumBands: FloatArray) {}
}